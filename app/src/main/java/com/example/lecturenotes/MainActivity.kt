package com.example.lecturenotes

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.lecturenotes.databinding.ActivityMainBinding
import com.example.lecturenotes.transcription.WhisperTranscriber
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var whisperTranscriber: WhisperTranscriber
    private var isRecording = false
    private var liveText = ""
    private var audioCollectionJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация Whisper (модель грузится один раз)
        whisperTranscriber = WhisperTranscriber(this)
        val modelLoaded = whisperTranscriber.initialize()
        if (!modelLoaded) {
            Log.e("MainActivity", "Failed to initialize Whisper model")
            binding.tvResult.text = "Ошибка: модель не загружена"
        }

        // Кнопка СТАРТ — запускаем RecordingService
        binding.btnStart.setOnClickListener {
            if (!isRecording && whisperTranscriber.isReady()) {
                startRecordingViaService()
            }
        }

        // Кнопка СТОП — останавливаем RecordingService
        binding.btnStop.setOnClickListener {
            if (isRecording) {
                stopRecordingViaService()
            }
        }
    }

    private fun startRecordingViaService() {
        isRecording = true
        liveText = ""
        binding.tvResult.text = ""
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true

        // Запускаем RecordingService через Intent
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        startService(intent)

        // Подписываемся на Flow аудио-чанков из Service
        audioCollectionJob = lifecycleScope.launch {
            RecordingService.audioChunks.collectLatest { chunk ->
                // Транскрибируем чанк через WhisperTranscriber
                val text = whisperTranscriber.processChunk(chunk, "ru")
                if (text.isNotEmpty()) {
                    liveText += " $text"
                    binding.tvResult.text = liveText.trim()
                }
            }
        }

        Log.i("MainActivity", "Recording started via RecordingService")
    }

    private fun stopRecordingViaService() {
        isRecording = false
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false

        // Отписываемся от Flow
        audioCollectionJob?.cancel()
        audioCollectionJob = null

        // Останавливаем RecordingService через Intent
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)

        Log.i("MainActivity", "Recording stopped. Final text length: ${liveText.length}")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Останавливаем запись если Activity уничтожается
        if (isRecording) {
            stopRecordingViaService()
        }
        // Освобождаем модель
        whisperTranscriber.shutdown()
    }
}