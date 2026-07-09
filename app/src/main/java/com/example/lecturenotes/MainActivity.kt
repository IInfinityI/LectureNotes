package com.example.lecturenotes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.io.FileOutputStream
import com.example.lecturenotes.TextProcessor

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "LectureNotes"
        init {
            System.loadLibrary("lecturenotes")
        }
    }

    external fun stringFromJNI(): String
    external fun transcribeAudio(audioPath: String, modelPath: String, language: String): String
    external fun transcribeChunk(audioData: ByteArray, modelPath: String, language: String): String

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) Toast.makeText(this, "ужны разрешения", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        copyModelToCache()
        setContent {
            LectureNotesTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StreamScreen()
                }
            }
        }
    }

    private fun copyModelToCache() {
        val modelFile = File(cacheDir, "ggml-tiny.bin")
        if (!modelFile.exists()) {
            assets.open("ggml-tiny.bin").use { input ->
                FileOutputStream(modelFile).use { output -> input.copyTo(output) }
            }
            }
    }

    private fun checkPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startRecording(context: Context) {
        val intent = Intent(context, RecordingService::class.java).apply { action = RecordingService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    private fun stopRecording(context: Context) {
        val intent = Intent(context, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP }
        context.startService(intent)
    }

    @Composable
    fun StreamScreen() {
        val context = LocalContext.current
        val activity = LocalContext.current as? MainActivity
        var isRecording by remember { mutableStateOf(false) }
        var isFinalizing by remember { mutableStateOf(false) }
        var liveText by remember { mutableStateOf("ажми кнопку для записи") }
        var streamingJob by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            Text("Lecture Notes", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            Spacer(modifier = Modifier.height(8.dp))
            Text("Стриминг распознавания", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(40.dp))

            RecordButton(
                isRecording = isRecording,
                isProcessing = isFinalizing,
                onClick = {
                    if (!activity!!.checkPermissions()) {
                        activity.requestPermissionLauncher.launch(activity.requiredPermissions)
                        return@RecordButton
                    }
                    if (isRecording) {
                        Log.i(TAG, "Stop clicked")
                        streamingJob?.cancel()
                        activity.stopRecording(context)
                        isRecording = false
                        isFinalizing = true
                        liveText = "инализация..."
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                delay(500)
                                val audioFile = File(cacheDir, RecordingService.RECORDING_FILE)
                                val modelFile = File(cacheDir, "ggml-tiny.bin")
                                if (audioFile.exists() && audioFile.length() > 32000) {
                                    TextProcessor.processText(activity.transcribeAudio(audioFile.absolutePath, modelFile.absolutePath, "ru"))
                                } else {
                                    "ет аудио данных"
                                }
                            }
                            Log.i(TAG, "Final result: $result")
                            liveText = if (result.isNullOrEmpty()) "устой результат" else result
                            isFinalizing = false
                        }
                    } else {
                        Log.i(TAG, "Start clicked")
                        AudioBuffer.clear()
                        activity.startRecording(context)
                        isRecording = true
                        liveText = "Слушаю... (обновление каждые 3 сек)"
                        val modelFile = File(cacheDir, "ggml-tiny.bin")
                        streamingJob = scope.launch {
                            while (isActive) {
                                delay(3000)
                                val audioData = AudioBuffer.getAllData()
                                Log.i(TAG, "Streaming tick: buffer size = ${audioData.size}")
                                if (audioData.size > 32000) {
                                    val chunkResult = withContext(Dispatchers.IO) {
                                        try {
                                            TextProcessor.processText(activity.transcribeChunk(audioData, modelFile.absolutePath, "ru"))
                                        } catch (e: Exception) {
                                            Log.e(TAG, "transcribeChunk error", e)
                                            ""
                                        }
                                    }
                                    Log.i(TAG, "Chunk result: '$chunkResult'")
                                    if (chunkResult.isNotEmpty()) {
                                        liveText = chunkResult
                                    }
                                }
                            }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (isFinalizing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        Text("инальная обработка...", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Card(
                modifier = Modifier.fillMaxWidth().animateContentSize(),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = when {
                            isFinalizing -> "инализация"
                            isRecording -> " реальном времени"
                            else -> "езультат"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isRecording) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = liveText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.animateContentSize()
                    )
                }
            }
        }
    }

    @Composable
    fun RecordButton(isRecording: Boolean, isProcessing: Boolean, onClick: () -> Unit) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = 1.15f,
            animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
            label = "pulseScale"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f, targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
            label = "pulseAlpha"
        )
        val gradient = if (isRecording)
            Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFF97316)), Offset.Zero, Offset.Infinite)
        else
            Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6)), Offset.Zero, Offset.Infinite)
        val icon: ImageVector = if (isRecording) Icons.Outlined.Stop else Icons.Outlined.Mic

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (isRecording) {
                Box(modifier = Modifier.size(120.dp).scale(pulseScale).alpha(pulseAlpha).background(gradient, CircleShape))
            }
            Button(
                onClick = onClick, enabled = !isProcessing,
                modifier = Modifier.size(120.dp).shadow(elevation = 12.dp, shape = CircleShape),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize().background(gradient), contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = when { isProcessing -> "бработка..."; isRecording -> "становить"; else -> "ачать запись" },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
        )
    }
}














