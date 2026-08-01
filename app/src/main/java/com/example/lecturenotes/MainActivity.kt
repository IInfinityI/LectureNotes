package com.example.lecturenotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.lecturenotes.databinding.ActivityMainBinding
import com.example.lecturenotes.transcription.WhisperTranscriber
import com.example.lecturenotes.ui.RecordingViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var whisperTranscriber: WhisperTranscriber
    private lateinit var recordingViewModel: RecordingViewModel
    
    private var liveText = ""
    private var isRecording = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Запрос разрешений
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
        
        // Инициализация WhisperTranscriber
        whisperTranscriber = WhisperTranscriber(this)
        val modelLoaded = whisperTranscriber.initialize()
        
        if (!modelLoaded) {
            Log.e("MainActivity", "Failed to initialize Whisper model")
            binding.tvResult.text = "Ошибка: модель не загружена"
        }
        
        // Инициализация RecordingViewModel
        recordingViewModel = RecordingViewModel(application)
        
        // Подписка на аудио-чанки от RecordingService
        lifecycleScope.launch {
            RecordingService.audioChunks.collect { chunk ->
                processAudioChunk(chunk)
            }
        }
        
        // Кнопка Start
        binding.btnStart.setOnClickListener {
            if (!isRecording && whisperTranscriber.isReady()) {
                startRecordingViaService()
            }
        }
        
        // Кнопка Stop
        binding.btnStop.setOnClickListener {
            if (isRecording) {
                stopRecordingViaService()
            }
        }
        
        // Начальное состояние кнопок
        binding.btnStop.isEnabled = false
    }
    
    private fun startRecordingViaService() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        
        isRecording = true
        liveText = ""
        binding.tvResult.text = ""
        
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true
        
        Log.i("MainActivity", "Recording started via service")
    }
    
    private fun stopRecordingViaService() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
        
        isRecording = false
        
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
        
        // Сохраняем запись в БД
        if (liveText.isNotEmpty()) {
            recordingViewModel.addRecording(liveText)
            Log.i("MainActivity", "Recording saved to database")
        }
        
        Log.i("MainActivity", "Recording stopped via service")
    }
    
    private fun processAudioChunk(chunk: ByteArray) {
        if (!isRecording) return
        
        // Транскрибация чанка через WhisperTranscriber
        lifecycleScope.launch {
            val text = whisperTranscriber.processChunk(chunk, "ru")
            
            if (text.isNotEmpty()) {
                // Обработка текста через TextProcessor
                val processedText = TextProcessor.processText(text)
                
                liveText += " $processedText"
                
                // Обновление UI
                runOnUiThread {
                    binding.tvResult.text = liveText.trim()
                }
                
                Log.d("MainActivity", "Processed chunk: $processedText")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Освобождаем модель при закрытии Activity
        whisperTranscriber.shutdown()
        Log.i("MainActivity", "Activity destroyed, model released")
    }
}