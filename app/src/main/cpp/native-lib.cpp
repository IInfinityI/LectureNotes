#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <fstream>
#include "whisper.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LectureNotes", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LectureNotes", __VA_ARGS__)

// Глобальный контекст модели (живет между вызовами)
static struct whisper_context* g_ctx = nullptr;
static std::string g_model_path = "";

// Конвертация UTF-8 → UTF-16
static jstring utf8ToJstring(JNIEnv* env, const std::string& utf8) {
    if (utf8.empty()) return env->NewStringUTF("");
    
    std::u16string utf16;
    for (size_t i = 0; i < utf8.size(); ) {
        uint32_t cp = 0;
        unsigned char c = utf8[i++];
        
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0) {
            cp = (c & 0x1F) << 6;
            if (i < utf8.size()) cp |= utf8[i++] & 0x3F;
        } else if ((c & 0xF0) == 0xE0) {
            cp = (c & 0x0F) << 12;
            if (i < utf8.size()) cp |= (utf8[i++] & 0x3F) << 6;
            if (i < utf8.size()) cp |= utf8[i++] & 0x3F;
        } else {
            cp = (c & 0x07) << 18;
            if (i < utf8.size()) cp |= (utf8[i++] & 0x3F) << 12;
            if (i < utf8.size()) cp |= (utf8[i++] & 0x3F) << 6;
            if (i < utf8.size()) cp |= utf8[i++] & 0x3F;
        }
        
        if (cp <= 0xFFFF) {
            utf16 += static_cast<char16_t>(cp);
        } else {
            cp -= 0x10000;
            utf16 += static_cast<char16_t>(0xD800 | (cp >> 10));
            utf16 += static_cast<char16_t>(0xDC00 | (cp & 0x3FF));
        }
    }
    
    return env->NewString(reinterpret_cast<const jchar*>(utf16.c_str()), utf16.length());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_MainActivity_stringFromJNI(JNIEnv* env, jobject) {
    return env->NewStringUTF("Whisper.cpp loaded");
}

// ИНИЦИАЛИЗАЦИЯ МОДЕЛИ (привязана к WhisperTranscriber)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_initModel(
    JNIEnv* env, jobject, jstring model_path) {
    
    const char* modelPath = env->GetStringUTFChars(model_path, 0);
    
    if (g_ctx != nullptr && g_model_path == modelPath) {
        env->ReleaseStringUTFChars(model_path, modelPath);
        LOGI("Model already loaded");
        return JNI_TRUE;
    }
    
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
    
    whisper_context_params cparams = whisper_context_default_params();
    g_ctx = whisper_init_from_file_with_params(modelPath, cparams);
    
    if (!g_ctx) {
        LOGE("Failed to load model from: %s", modelPath);
        env->ReleaseStringUTFChars(model_path, modelPath);
        return JNI_FALSE;
    }
    
    g_model_path = modelPath;
    env->ReleaseStringUTFChars(model_path, modelPath);
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

// ОСВОБОЖДЕНИЕ МОДЕЛИ (привязана к WhisperTranscriber)
extern "C" JNIEXPORT void JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_releaseModel(JNIEnv* env, jobject) {
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        g_model_path = "";
        LOGI("Model released");
    }
}

// ТРАНСКРИБАЦИЯ ЧАНКА (привязана к WhisperTranscriber)
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_transcribeChunk(
    JNIEnv* env, jobject, jbyteArray audio_data, jstring language) {
    
    if (g_ctx == nullptr) {
        LOGE("Model not initialized! Call initModel() first");
        return env->NewStringUTF("");
    }
    
    jsize length = env->GetArrayLength(audio_data);
    if (length < 32000) return env->NewStringUTF("");
    
    jbyte* buffer = env->GetByteArrayElements(audio_data, 0);
    short* pcm_s16 = reinterpret_cast<short*>(buffer);
    size_t samples = length / sizeof(short);
    std::vector<float> pcmf32(samples);
    for (size_t i = 0; i < samples; i++) {
        pcmf32[i] = static_cast<float>(pcm_s16[i]) / 32768.0f;
    }
    env->ReleaseByteArrayElements(audio_data, buffer, 0);
    
    const char* lang = env->GetStringUTFChars(language, 0);
    
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = lang;
    wparams.n_threads = 4;
    wparams.no_context = true;
    
    std::string text = "";
    if (whisper_full(g_ctx, wparams, pcmf32.data(), pcmf32.size()) == 0) {
        int n = whisper_full_n_segments(g_ctx);
        for (int i = 0; i < n; ++i) {
            text += whisper_full_get_segment_text(g_ctx, i);
        }
    }
    
    env->ReleaseStringUTFChars(language, lang);
    return utf8ToJstring(env, text);
}

// ТРАНСКРИБАЦИЯ АУДИОФАЙЛА (привязана к WhisperTranscriber)
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_transcribeAudio(
    JNIEnv* env, jobject, jstring audio_path, jstring language) {
    
    if (g_ctx == nullptr) {
        LOGE("Model not initialized! Call initModel() first");
        return env->New