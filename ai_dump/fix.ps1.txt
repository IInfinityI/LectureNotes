$cpp = @"
#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <android/log.h>
#include "whisper.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LectureNotes", __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_MainActivity_stringFromJNI(JNIEnv* env, jobject) {
    return env->NewStringUTF("Whisper.cpp loaded");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_lecturenotes_MainActivity_transcribeChunk(
    JNIEnv* env, jobject, jbyteArray audio_data, jstring model_path, jstring language) {
    jsize length = env->GetArrayLength(audio_data);
    if (length < 32000) return env->NewByteArray(0);
    jbyte* buffer = env->GetByteArrayElements(audio_data, 0);
    short* pcm_s16 = reinterpret_cast<short*>(buffer);
    size_t samples = length / sizeof(short);
    std::vector<float> pcmf32(samples);
    for (size_t i = 0; i < samples; i++) pcmf32[i] = static_cast<float>(pcm_s16[i]) / 32768.0f;
    env->ReleaseByteArrayElements(audio_data, buffer, 0);
    const char *modelPath = env->GetStringUTFChars(model_path, 0);
    const char *lang = env->GetStringUTFChars(language, 0);
    whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context * ctx = whisper_init_from_file_with_params(modelPath, cparams);
    env->ReleaseStringUTFChars(model_path, modelPath);
    if (!ctx) { env->ReleaseStringUTFChars(language, lang); return env->NewByteArray(0); }
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false; wparams.print_special = false;
    wparams.print_realtime = false; wparams.print_timestamps = false;
    wparams.translate = false; wparams.language = lang; wparams.n_threads = 4; wparams.no_context = true;
    std::string text = "";
    if (whisper_full(ctx, wparams, pcmf32.data(), pcmf32.size()) == 0) {
        int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; ++i) text += whisper_full_get_segment_text(ctx, i);
    }
    whisper_free(ctx);
    env->ReleaseStringUTFChars(language, lang);
    jbyteArray result = env->NewByteArray(text.size());
    env->SetByteArrayRegion(result, 0, text.size(), reinterpret_cast<const jbyte*>(text.c_str()));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_lecturenotes_MainActivity_transcribeAudio(
    JNIEnv* env, jobject, jstring audio_path, jstring model_path, jstring language) {
    return env->NewByteArray(0);
}
"@
Set-Content -Path "app\src\main\cpp\native-lib.cpp" -Value $cpp -Encoding UTF8

$kt = @"
package com.example.lecturenotes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lecturenotes.ui.theme.LectureNotesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    companion object { init { System.loadLibrary("lecturenotes") } }
    external fun stringFromJNI(): String
    external fun transcribeAudio(audioPath: String, modelPath: String, language: String): ByteArray
    external fun transcribeChunk(audioData: ByteArray, modelPath: String, language: String): ByteArray

    private val reqPerms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO)
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!File(cacheDir, "ggml-tiny.bin").exists()) {
            try { assets.open("ggml-tiny.bin").use { i -> File(cacheDir, "ggml-tiny.bin").outputStream().use { o -> i.copyTo(o) } } } catch (e: Exception) {}
        }
        setContent { LectureNotesTheme { Surface(modifier = Modifier.fillMaxSize()) { StreamScreen() } } }
    }

    @Composable
    fun StreamScreen() {
        val context = LocalContext.current
        val activity = LocalContext.current as MainActivity
        var isRec by remember { mutableStateOf(false) }
        var liveText by remember { mutableStateOf("ажми кнопку") }
        var job by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()

        Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Button(onClick = {
                if (reqPerms.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) { permLauncher.launch(reqPerms); return@Button }
                if (isRec) {
                    job?.cancel(); isRec = false
                    context.stopService(Intent(context, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP })
                } else {
                    AudioBuffer.clear()
                    context.startService(Intent(context, RecordingService::class.java).apply { action = RecordingService.ACTION_START })
                    isRec = true; liveText = "Слушаю..."
                    job = scope.launch {
                        val mf = File(cacheDir, "ggml-tiny.bin")
                        while (isActive) {
                            delay(3000)
                            val data = AudioBuffer.getAllData()
                            if (data.size > 32000) {
                                val res = withContext(Dispatchers.IO) { activity.transcribeChunk(data, mf.absolutePath, "ru") }
                                val decoded = String(res, Charsets.UTF_8)
                                withContext(Dispatchers.Main) { Toast.makeText(context, decoded, Toast.LENGTH_LONG).show() }
                                if (decoded.isNotBlank()) liveText = decoded
                            }
                        }
                    }
                }
            }) { Text(if (isRec) "становить" else "ачать") }
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth()) { Text(liveText, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}
"@
Set-Content -Path "app\src\main\java\com\example\lecturenotes\MainActivity.kt" -Value $kt -Encoding UTF8
