package com.example.lecturenotes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream // <-- Добавлен импорт
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
    companion object {
        const val CHANNEL_ID = "RecordingChannel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val RECORDING_FILE = "recording_live.pcm"

        // Константы для 2-секундных чанков
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_DURATION_SECONDS = 2
        private const val CHUNK_SIZE_BYTES = SAMPLE_RATE * CHUNK_DURATION_SECONDS * 2 // 16-bit = 2 байта
    }

    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    // Буфер для накопления 2-секундных чанков
    private val chunkBuffer = ByteArrayOutputStream()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i("RecordingService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundService()
                startRecording()
            }
            ACTION_STOP -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PAUSE -> {
                pauseRecording()
            }
            ACTION_RESUME -> {
                resumeRecording()
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Запись лекции")
            .setContentText("Идёт запись и распознавание...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
        Log.i("RecordingService", "Foreground service started")
    }

    private fun startRecording() {
        if (isRecording.getAndSet(true)) {
            Log.w("RecordingService", "Already recording")
            return
        }

        isPaused.set(false)

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e("RecordingService", "Invalid buffer size: $bufferSize")
            isRecording.set(false)
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("RecordingService", "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                isRecording.set(false)
                return
            }

            val outputFile = File(cacheDir, RECORDING_FILE) // <-- Теперь File доступен
            if (outputFile.exists()) outputFile.delete()

            audioRecord?.startRecording()
            Log.i("RecordingService", "Recording started")

            recordingThread = Thread {
                FileOutputStream(outputFile).use { fos -> // <-- Теперь FileOutputStream доступен
                    val buffer = ByteArray(bufferSize)

                    while (isRecording.get()) {
                        if (isPaused.get()) {
                            // При паузе просто ждём, не читаем данные
                            Thread.sleep(100)
                            continue
                        }

                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                        if (read > 0) {
                            // Записываем в файл (для отладки)
                            fos.write(buffer, 0, read)

                            // Накапливаем в буфер
                            synchronized(chunkBuffer) {
                                chunkBuffer.write(buffer, 0, read)

                                // Когда буфер достигает 2 секунд - отправляем чанк
                                while (chunkBuffer.size() >= CHUNK_SIZE_BYTES) {
                                    val chunkData = chunkBuffer.toByteArray()
                                    val chunk = if (chunkData.size > CHUNK_SIZE_BYTES) {
                                        // Если буфер больше 2 секунд - берём первые 2 секунды
                                        val exactChunk = ByteArray(CHUNK_SIZE_BYTES)
                                        System.arraycopy(chunkData, 0, exactChunk, 0, CHUNK_SIZE_BYTES)

                                        // Очищаем буфер и сохраняем остаток
                                        chunkBuffer.reset()
                                        if (chunkData.size > CHUNK_SIZE_BYTES) {
                                            chunkBuffer.write(chunkData, CHUNK_SIZE_BYTES, chunkData.size - CHUNK_SIZE_BYTES)
                                        }
                                        exactChunk
                                    } else {
                                        // Ровно 2 секунды
                                        chunkBuffer.reset()
                                        chunkData
                                    }

                                    // Отправляем чанк в Repository через корутину (новый способ)
                                    CoroutineScope(Dispatchers.Main.immediate).launch {
                                        AudioChunkRepository.emitChunk(chunk)
                                    }
                                    Log.d("RecordingService", "Emitted chunk: ${chunk.size} bytes")
                                }
                            }
                        }
                    }
                }
            }

            recordingThread?.start()

        } catch (e: SecurityException) {
            Log.e("RecordingService", "Permission denied: ${e.message}")
            isRecording.set(false)
        } catch (e: Exception) {
            Log.e("RecordingService", "Error starting recording: ${e.message}", e)
            isRecording.set(false)
        }
    }

    private fun pauseRecording() {
        if (!isRecording.get()) {
            Log.w("RecordingService", "Cannot pause: not recording")
            return
        }

        isPaused.set(true)
        Log.i("RecordingService", "Recording paused")
    }

    private fun resumeRecording() {
        if (!isRecording.get()) {
            Log.w("RecordingService", "Cannot resume: not recording")
            return
        }

        if (!isPaused.get()) {
            Log.w("RecordingService", "Cannot resume: not paused")
            return
        }

        isPaused.set(false)
        Log.i("RecordingService", "Recording resumed")
    }

    private fun stopRecording() {
        isRecording.set(false)
        isPaused.set(false)

        recordingThread?.join(2000)

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // Очищаем буфер
        synchronized(chunkBuffer) {
            chunkBuffer.reset()
        }

        Log.i("RecordingService", "Recording stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Запись лекций",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording.get()) {
            stopRecording()
        }
        Log.i("RecordingService", "Service destroyed")
    }
}

// Вспомогательный класс для накопления байтов
private class ByteArrayOutputStream {
    private val buffer = mutableListOf<Byte>()

    fun write(data: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            buffer.add(data[i])
        }
    }

    fun size(): Int = buffer.size

    fun toByteArray(): ByteArray = buffer.toByteArray()

    fun reset() {
        buffer.clear()
    }
}