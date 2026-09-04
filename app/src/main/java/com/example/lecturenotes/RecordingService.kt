package com.example.lecturenotes

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.lecturenotes.data.RecordingRepository
import com.example.lecturenotes.transcription.TranscriptionManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "RecordingChannel"
        private const val NOTIFICATION_ID = 1

        // Параметры аудиозаписи
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        private const val CHUNK_DURATION_MS = 3000L // 3 секунды на чанк
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null

    // Параметры, передаваемые из MainActivity
    private var language: String = "ru"
    private var modelName: String = "ggml-tiny.bin"
    private lateinit var transcriptionManager: TranscriptionManager

    // Путь к аудиофайлу (полный)
    private lateinit var audioFile: File

    // Буфер для накопления всех чанков (для сохранения целого файла)
    private val fullAudioBuffer = mutableListOf<ByteArray>()

    // Репозиторий для сохранения в БД
    private lateinit var repository: RecordingRepository

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        repository = RecordingRepository.getInstance(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            language = it.getStringExtra("LANGUAGE") ?: "ru"
            modelName = it.getStringExtra("MODEL_NAME") ?: "ggml-tiny.bin"
            // TranscriptionManager должен быть передан как Parcelable? - лучше передать через синглтон или получить здесь
            // Временно создадим новый экземпляр (потом можно переделать)
            transcriptionManager = TranscriptionManager(applicationContext)
        }
        startRecording()
        return START_STICKY
    }

    private fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "Recording already in progress")
            return
        }

        // Создаём файл для аудио в папке приложения
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val audioDir = File(filesDir, "recordings")
        if (!audioDir.exists()) audioDir.mkdirs()
        audioFile = File(audioDir, "recording_$timeStamp.pcm")

        // Инициализируем AudioRecord
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            BUFFER_SIZE
        ).apply {
            if (state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                stopSelf()
                return
            }
        }

        isRecording = true
        fullAudioBuffer.clear()

        // Запускаем фоновую запись
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(BUFFER_SIZE)
            audioRecord?.startRecording()
            Log.i(TAG, "Recording started")

            // Счётчик для чанков
            var chunkCounter = 0
            var chunkBuffer = mutableListOf<ByteArray>()

            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val data = buffer.copyOf(read)
                    fullAudioBuffer.add(data) // сохраняем для итогового файла
                    chunkBuffer.add(data)

                    // Если накопилось достаточно данных (по времени), отправляем на транскрипцию
                    val chunkSizeBytes = (SAMPLE_RATE * 2 * CHUNK_DURATION_MS / 1000).toInt() // 2 байта на сэмпл
                    var totalChunkBytes = chunkBuffer.sumOf { it.size }
                    if (totalChunkBytes >= chunkSizeBytes) {
                        // Объединяем чанк в один массив
                        val chunkData = chunkBuffer.flatMap { it.toList() }.toByteArray()
                        chunkBuffer.clear()
                        chunkCounter++

                        // Отправляем на транскрипцию
                        try {
                            val transcribedText = transcriptionManager.transcribeChunk(
                                context = applicationContext,
                                audioData = chunkData,
                                sampleRate = SAMPLE_RATE,
                                modelAssetName = modelName,
                                language = language
                            )
                            if (transcribedText.isNotBlank()) {
                                // Отправляем текст в MainActivity через Broadcast или ViewModel
                                sendTranscriptionResult(transcribedText)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Transcription error: ${e.message}")
                        }
                    }
                } else {
                    // Пауза, чтобы не грузить процессор
                    delay(50)
                }
            }

            // Остановка записи
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            // Сохраняем полный аудиофайл
            saveFullAudioFile()

            // Сохраняем запись в БД
            saveRecordingToDatabase()

            Log.i(TAG, "Recording stopped")
            stopSelf()
        }

        // Показываем уведомление
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun saveFullAudioFile() {
        try {
            FileOutputStream(audioFile).use { fos ->
                fullAudioBuffer.forEach { fos.write(it) }
            }
            Log.i(TAG, "Audio saved to ${audioFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save audio file: ${e.message}")
        }
    }

    private fun saveRecordingToDatabase() {
        // Получаем финальный текст из транскрипции (у нас он накапливается в ViewModel, но здесь мы его не храним)
        // Для простоты – возьмём последний транскрипт из буфера, или просто сохраним с меткой времени
        // В реальности нужно хранить накопленный текст. Пока используем заглушку.
        val finalText = getFinalTranscription() // нужно реализовать сбор текста

        if (finalText.isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                repository.insertRecording(
                    title = "Запись ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())}",
                    text = finalText,
                    audioFilePath = audioFile.absolutePath
                )
                Log.i(TAG, "Recording saved to database")
            }
        } else {
            Log.w(TAG, "Empty transcription, recording not saved")
        }
    }

    // Временное хранилище текста (в реальном приложении лучше использовать SharedFlow в ViewModel)
    private val transcriptionBuffer = StringBuilder()

    private fun sendTranscriptionResult(text: String) {
        transcriptionBuffer.append(text).append(" ")
        // Отправляем широковещательное сообщение для MainActivity
        val intent = Intent("TRANSCRIPTION_UPDATE")
        intent.putExtra("text", text)
        sendBroadcast(intent)
    }

    private fun getFinalTranscription(): String {
        return transcriptionBuffer.toString().trim()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }

    private fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Уведомления ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Запись лекций",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление во время записи аудио"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Идёт запись")
            .setContentText("Транскрипция в реальном времени...")
            .setSmallIcon(android.R.drawable.ic_menu_mic)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}