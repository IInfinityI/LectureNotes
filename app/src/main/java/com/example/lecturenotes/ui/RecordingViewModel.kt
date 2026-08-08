package com.example.lecturenotes.ui

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.lecturenotes.RecordingService
import com.example.lecturenotes.data.AppDatabase
import com.example.lecturenotes.data.AudioChunkRepository
import com.example.lecturenotes.data.Recording
import com.example.lecturenotes.transcription.TranscriptionState
import com.example.lecturenotes.transcription.WhisperTranscriber
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecordingViewModel(
    application: Application,
    private val settingsViewModel: SettingsViewModel
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RecordingViewModel"
    }

    private val dao = AppDatabase.getDatabase(application).recordingDao()
    private val transcriber = WhisperTranscriber(application)

    // --- UI State для StreamingScreen ---
    private val _uiState = MutableStateFlow(TranscriptionState())
    val uiState: StateFlow<TranscriptionState> = _uiState.asStateFlow()

    // --- Список записей из БД (Flow) ---
    val recordings: StateFlow<List<Recording>> = dao.getAllRecordings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    // --- Ошибки ---
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var audioCollectionJob: Job? = null
    private var settingsJob: Job? = null

    init {
        // Инициализация модели Whisper с настройками по умолчанию
        viewModelScope.launch {
            val modelSize = settingsViewModel.modelSize.value
            val success = initializeModelWithSettings(modelSize)
            if (!success) {
                _uiState.value = _uiState.value.copy(error = "Не удалось загрузить модель Whisper")
                Log.e(TAG, "Failed to initialize Whisper model")
            }
        }

        // Подписка на изменения настроек
        settingsJob = viewModelScope.launch {
            settingsViewModel.settingsChanged.collect { timestamp ->
                // Если запись не идёт — переинициализируем модель
                if (!_uiState.value.isRecording) {
                    val modelSize = settingsViewModel.modelSize.value
                    Log.i(TAG, "Settings changed, reinitializing model: $modelSize")
                    initializeModelWithSettings(modelSize)
                } else {
                    Log.w(TAG, "Settings changed but recording is active - changes will apply after stop")
                }
            }
        }
    }

    /**
     * Инициализация модели с учётом настроек.
     */
    private suspend fun initializeModelWithSettings(modelSize: String): Boolean {
        val modelName = "ggml-${modelSize}.bin"
        Log.i(TAG, "Initializing model: $modelName")
        return transcriber.initialize(modelName)
    }

    /**
     * Запуск записи через RecordingService + подписка на аудио-чанки.
     */
    fun startRecording() {
        if (_uiState.value.isRecording) return

        // Очищаем предыдущее состояние
        _uiState.value = TranscriptionState(isRecording = true)

        // Запускаем сервис записи
        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        context.startForegroundService(intent)

        // Читаем текущие настройки
        val currentLanguage = settingsViewModel.language.value

        // Подписываемся на аудио-чанки из Repository (новый способ)
        audioCollectionJob = viewModelScope.launch {
            AudioChunkRepository.audioChunks.collect { chunk ->
                val text = transcriber.processChunk(chunk, currentLanguage)
                if (text.isNotBlank()) {
                    val currentLive = _uiState.value.liveText
                    val newLive = if (currentLive.isEmpty()) text.trim() else "$currentLive $text".trim()
                    _uiState.value = _uiState.value.copy(
                        liveText = newLive,
                        wordCount = newLive.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                    )
                }
            }
        }
    }

    /**
     * Остановка записи. Финализированный текст остаётся в state.
     */
    fun stopRecording() {
        if (!_uiState.value.isRecording) return

        _uiState.value = _uiState.value.copy(isFinalizing = true)

        // Останавливаем сервис
        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)

        // Отписываемся от аудио
        audioCollectionJob?.cancel()
        audioCollectionJob = null

        // Переносим liveText в finalizedText
        val finalText = _uiState.value.fullText
        _uiState.value = _uiState.value.copy(
            isRecording = false,
            isFinalizing = false,
            finalizedText = finalText,
            liveText = ""
        )
    }

    /**
     * Сохранение текущей транскрибации в БД.
     */
    fun saveRecording() {
        val text = _uiState.value.fullText
        if (text.isBlank()) {
            _errorMessage.value = "Нечего сохранять: текст пустой"
            return
        }

        viewModelScope.launch {
            try {
                val recording = Recording(
                    title = "Запись ${System.currentTimeMillis()}",
                    transcription = text.trim()
                )
                dao.insert(recording)
                Log.i(TAG, "Recording saved successfully")

                // Очищаем state после сохранения
                _uiState.value = TranscriptionState()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving recording", e)
                _errorMessage.value = "Ошибка сохранения: ${e.message}"
            }
        }
    }

    /**
     * Удаление записи из БД.
     */
    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            try {
                dao.delete(recording)
                Log.i(TAG, "Recording deleted: ${recording.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting recording", e)
                _errorMessage.value = "Ошибка удаления: ${e.message}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        transcriber.shutdown()
        audioCollectionJob?.cancel()
        settingsJob?.cancel()
        Log.i(TAG, "ViewModel cleared")
    }
}

class RecordingViewModelFactory(
    private val application: Application,
    private val settingsViewModel: SettingsViewModel
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RecordingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RecordingViewModel(application, settingsViewModel) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}