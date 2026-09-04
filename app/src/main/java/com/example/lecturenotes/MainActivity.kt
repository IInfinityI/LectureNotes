package com.example.lecturenotes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.lecturenotes.data.RecordingRepository
import com.example.lecturenotes.transcription.TranscriptionManager
import com.example.lecturenotes.ui.RecordingViewModel
import com.example.lecturenotes.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    // ViewModels
    private lateinit var recordingViewModel: RecordingViewModel
    private lateinit var settingsViewModel: SettingsViewModel

    // UI элементы
    private lateinit var tvTranscription: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnStop: Button
    private lateinit var btnHistory: Button
    private lateinit var btnSettings: Button

    // Транскрипция
    private lateinit var transcriptionManager: TranscriptionManager

    // Параметры из настроек (обновляются при старте записи)
    private var currentLanguage: String = "ru"
    private var currentModel: String = "ggml-tiny.bin"

    // Флаг записи
    private var isRecording = false

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация ViewModels
        recordingViewModel = ViewModelProvider(this).get(RecordingViewModel::class.java)
        settingsViewModel = ViewModelProvider(this).get(SettingsViewModel::class.java)

        // Инициализация UI
        tvTranscription = findViewById(R.id.tvTranscription)
        btnRecord = findViewById(R.id.btnRecord)
        btnStop = findViewById(R.id.btnStop)
        btnHistory = findViewById(R.id.btnHistory)
        btnSettings = findViewById(R.id.btnSettings)

        // Инициализация менеджера транскрипции
        transcriptionManager = TranscriptionManager(this)

        // Подписка на изменения настроек (обновляем переменные)
        lifecycleScope.launch {
            settingsViewModel.selectedLanguage.collectLatest { lang ->
                currentLanguage = lang
            }
        }
        lifecycleScope.launch {
            settingsViewModel.selectedModel.collectLatest { model ->
                currentModel = model
            }
        }

        // Подписка на транскрипцию из RecordingViewModel
        lifecycleScope.launch {
            recordingViewModel.transcriptionText.collectLatest { text ->
                tvTranscription.text = text
            }
        }

        // Подписка на статус записи
        lifecycleScope.launch {
            recordingViewModel.isRecording.collectLatest { recording ->
                isRecording = recording
                updateButtons()
            }
        }

        // Кнопка "Записать"
        btnRecord.setOnClickListener {
            if (checkPermissions()) {
                startRecording()
            } else {
                requestPermissions()
            }
        }

        // Кнопка "Стоп"
        btnStop.setOnClickListener {
            stopRecording()
        }

        // Кнопка "История" (пока просто Toast)
        btnHistory.setOnClickListener {
            Toast.makeText(this, "История записей (будет позже)", Toast.LENGTH_SHORT).show()
            // TODO: открыть HistoryActivity
        }

        // Кнопка "Настройки" (пока просто Toast)
        btnSettings.setOnClickListener {
            Toast.makeText(this, "Настройки (будут позже)", Toast.LENGTH_SHORT).show()
            // TODO: открыть SettingsActivity
        }

        // Проверяем разрешения при старте
        if (!checkPermissions()) {
            requestPermissions()
        }
    }

    // Проверка разрешений
    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Разрешение получено", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Разрешение необходимо для записи", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Запуск записи
    private fun startRecording() {
        if (!checkPermissions()) {
            Toast.makeText(this, "Нет разрешения на запись", Toast.LENGTH_SHORT).show()
            return
        }

        // Берём актуальные параметры из настроек (на всякий случай)
        currentLanguage = settingsViewModel.getCurrentLanguage()
        currentModel = settingsViewModel.getCurrentModel()

        // Очищаем предыдущий текст
        recordingViewModel.clearTranscription()

        // Запускаем сервис записи с параметрами
        recordingViewModel.startRecording(
            context = this,
            language = currentLanguage,
            modelName = currentModel,
            transcriptionManager = transcriptionManager
        )
        isRecording = true
        updateButtons()
    }

    // Остановка записи
    private fun stopRecording() {
        recordingViewModel.stopRecording()
        isRecording = false
        updateButtons()
        // Сохраняем итоговую транскрипцию в историю (пока через репозиторий)
        lifecycleScope.launch {
            val finalText = recordingViewModel.getFinalTranscription()
            if (finalText.isNotBlank()) {
                val repository = RecordingRepository.getInstance(applicationContext)
                // Сохраняем запись (путь к аудио пока заглушка)
                repository.insertRecording(
                    title = "Запись ${System.currentTimeMillis()}",
                    text = finalText,
                    audioFilePath = "" // позже добавим реальный путь
                )
                Toast.makeText(this@MainActivity, "Запись сохранена", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Обновление состояния кнопок
    private fun updateButtons() {
        btnRecord.isEnabled = !isRecording
        btnStop.isEnabled = isRecording
        // Если запись идёт, показываем статус
        if (isRecording) {
            btnRecord.text = "Идёт запись..."
        } else {
            btnRecord.text = "Записать"
        }
    }

    // Освобождение ресурсов при уничтожении
    override fun onDestroy() {
        super.onDestroy()
        // Останавливаем запись, если активна
        if (isRecording) {
            recordingViewModel.stopRecording()
        }
        // Освобождаем модель Whisper (опционально)
        transcriptionManager.releaseModel()
    }
}