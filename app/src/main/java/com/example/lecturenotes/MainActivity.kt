package com.example.lecturenotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.lecturenotes.databinding.ActivityMainBinding
import com.example.lecturenotes.transcription.WhisperTranscriber
import com.example.lecturenotes.ui.RecordingViewModel
import com.example.lecturenotes.ui.RecordingViewModelFactory
import com.example.lecturenotes.ui.SettingsViewModel
import com.example.lecturenotes.ui.SettingsViewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var whisperTranscriber: WhisperTranscriber
    private lateinit var recordingViewModel: RecordingViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    
    private var liveText = ""
    private var isRecording = false
    private var currentLanguage = "ru"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Запрос разрешений
        requestAudioPermission()
        
        // Инициализация ViewModels
        recordingViewModel = ViewModelProvider(
            this,
            RecordingViewModelFactory(application)
        )[RecordingViewModel::class.java]
        
        settingsViewModel = ViewModelProvider(
            this,
            SettingsViewModelFactory(application)
        )[SettingsViewModel::class.java]
        
        // Инициализация WhisperTranscriber с настройками из SettingsViewModel
        whisperTranscriber = WhisperTranscriber(this)
        initializeWhisperWithSettings()
        
        // Подписка на аудио-чанки от RecordingService
        lifecycleScope.launch {
            RecordingService.audioChunks.collect { chunk ->
                processAudioChunk(chunk)
            }
        }
        
        // Подписка на изменения языка
        lifecycleScope.launch {
            settingsViewModel.language.collect { lang ->
                currentLanguage = lang
                Log.d(TAG, "Language updated: $lang")
            }
        }
        
        // Кнопка Start
        binding.btnStart.setOnClickListener {
            if (!isRecording && whisperTranscriber.isReady()) {
                startRecordingViaService()
            } else if (!whisperTranscriber.isReady()) {
                binding.tvResult.text = "Модель ещё не загружена, подождите..."
                Log.w(TAG, "Cannot start: model not ready")
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
        
        Log.i(TAG, "MainActivity created")
    }
    
    /**
     * Инициализация Whisper с настройками из SettingsViewModel
     */
    private fun initializeWhisperWithSettings() {
        lifecycleScope.launch {
            try {
                // Читаем настройки из DataStore
                val modelSize = settingsViewModel.modelSize.first()
                val language = settingsViewModel.language.first()
                
                currentLanguage = language
                
                Log.i(TAG, "Initializing Whisper with model: $modelSize, language: $language")
                
                // Инициализируем модель
                val modelLoaded = whisperTranscriber.initialize()
                
                if (!modelLoaded) {
                    Log.e(TAG, "Failed to initialize Whisper model")
                    binding.tvResult.text = "Ошибка: модель не загружена"
                } else {
                    Log.i(TAG, "Whisper model initialized successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Whisper: ${e.message}", e)
                binding.tvResult.text = "Ошибка инициализации: ${e.message}"
            }
        }
    }
    
    /**
     * Запрос разрешения на запись аудио
     */
    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        }
    }
    
    /**
     * Запуск записи через RecordingService
     */
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
        
        Log.i(TAG, "Recording started via service")
    }
    
    /**
     * Остановка записи через RecordingService
     */
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
            Log.i(TAG, "Recording saved to database")
        }
        
        Log.i(TAG, "Recording stopped via service")
    }
    
    /**
     * Обработка аудио-чанка
     */
    private fun processAudioChunk(chunk: ByteArray) {
        if (!isRecording) return
        
        // Транскрибация чанка через WhisperTranscriber
        lifecycleScope.launch {
            val text = whisperTranscriber.processChunk(chunk, currentLanguage)
            
            if (text.isNotEmpty()) {
                // Обработка текста через TextProcessor
                val processedText = TextProcessor.processText(text)
                
                liveText += " $processedText"
                
                // Обновление UI
                runOnUiThread {
                    binding.tvResult.text = liveText.trim()
                }
                
                Log.d(TAG, "Processed chunk: $processedText")
            }
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
       