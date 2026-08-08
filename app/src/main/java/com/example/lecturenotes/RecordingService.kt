package com.example.lecturenotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.lecturenotes.data.AudioChunkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground Service для записи аудио в фоне.
 * Публикует аудио-чанки через AudioChunkRepository (SharedFlow).
 * Сохраняет полное аудио в файл для финальной транскрибации.
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.example.lecturenotes.START_RECORDING"
        const val ACTION_STOP = "com.example.lecturenotes.STOP_RECORDING"

        // Аудио-параметры
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // Размер чанка: 2 секунды аудио
        private const val CHUNK_DURATION_SECONDS = 2
        private const val CHUNK_SIZE_SAMPLES = SAMPLE_RATE * CHUNK_DURATION_SECONDS
        private const val CHUNK_SIZE_BYTES = CHUNK_SIZE_SAMPLES * 2 // 16-bit = 2 bytes per sample

        private const val RECORDING_FILE = "recording_live.pcm"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.IDLE)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    // Файл для сохранения полного аудио
    private var outputFile: File? = null
    private var fileOutputStream: FileOutputStream? = null

    enum class ServiceState {
        IDLE,
        RECORDING,
        ERROR_MICROPHONE,
        ERROR_PERMISSION,
        ERROR_STORAGE
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startRecording()
                startForeground(NOTIFICATION_ID, createNotification("Запись лекции..."))
            }
            ACTION_STOP -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        // Проверяем разрешение на запись аудио
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != 
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "RECORD_AUDIO permission not granted")
                _serviceState.value = ServiceState.ERROR_PERMISSION
                AudioChunkRepository.emitError("Нет разрешения на использование микрофона")
                stopSelf()
                return
            }
        }

        // Инициализация AudioRecord
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $minBufferSize")
            _serviceState.value = ServiceState.ERROR_MICROPHONE
            AudioChunkRepository.emitError("Не удалось инициализировать аудио-буфер")
            stopSelf()
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2 // Увеличенный буфер для стабильности
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord", e)
            _serviceState.value = ServiceState.ERROR_PERMISSION
            AudioChunkRepository.emitError("Нет разрешения на использование микрофона")
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            _serviceState.value = ServiceState.ERROR_MICROPHONE
            AudioChunkRepository.emitError("Не удалось получить доступ к микрофону: ${e.message}")
            stopSelf()
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            _serviceState.value = ServiceState.ERROR_MICROPHONE
            AudioChunkRepository.emitError("Микрофон занят другим приложением")
            audioRecord?.release()
            audioRecord = null
            stopSelf()
            return
        }

        // Создаём файл для сохранения полного аудио
        try {
            outputFile = File(cacheDir, RECORDING_FILE)
            fileOutputStream = FileOutputStream(outputFile, false) // Перезаписываем существующий
            Log.i(TAG, "Recording to: ${outputFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create output file", e)
            _serviceState.value = ServiceState.ERROR_STORAGE
            AudioChunkRepository.emitError("Не удалось создать файл для записи: ${e.message}")
            audioRecord?.release()
            audioRecord = null
            stopSelf()
            return
        }

        audioRecord?.startRecording()
        isRecording = true
        _serviceState.value = ServiceState.RECORDING

        Log.i(TAG, "Recording started")

        recordingJob = serviceScope.launch {
            val buffer = ShortArray(CHUNK_SIZE_SAMPLES)
            
            while (isRecording) {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (readCount > 0) {
                    // Конвертируем ShortArray в ByteArray (little-endian)
                    val byteData = ByteArray(readCount * 2)
                    for (i in 0 until readCount) {
                        val sample = buffer[i]
                        byteData[i * 2] = (sample.toInt() and 0xFF).toByte()
                        byteData[i * 2 + 1] = (sample.toInt() shr 8).toByte()
                    }

                    // Сохраняем в файл
                    try {
                        fileOutputStream?.write(byteData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to write to file", e)
                        AudioChunkRepository.emitError("Ошибка записи в файл: ${e.message}")
                        break
                    }

                    // Публикуем чанк через Repository
                    AudioChunkRepository.emitChunk(byteData)
                    
                } else if (readCount < 0) {
                    Log.e(TAG, "AudioRecord read error: $readCount")
                    AudioChunkRepository.emitError("Ошибка чтения из микрофона")
                    break
                }
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "Not recording")
            return
        }

        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        try {
            fileOutputStream?.flush()
            fileOutputStream?.close()
            fileOutputStream = null
            Log.i(TAG, "Recording stopped. File size: ${outputFile?.length()} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing file output stream", e)
        }

        _serviceState.value = ServiceState.IDLE
        Log.i(TAG, "Recording stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Запись лекций",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления для записи лекций"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lecture Notes")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
    }
}