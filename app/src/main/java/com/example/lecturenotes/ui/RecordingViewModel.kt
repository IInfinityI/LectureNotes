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
import com.example.lecturenotes.textprocessor.DefaultTextProcessor
import com.example.lecturenotes.textprocessor.TextProcessor
import com.example.lecturenotes.transcription.TranscriptionState
import com.example.lecturenotes.transcription.WhisperTranscriber
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class RecordingViewModel(
    application: Application,
    private val settingsViewModel: SettingsViewModel
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RecordingViewModel"
        private const val RECORDING_FILE = "recording_live.pcm"
        private const val STREAMING_MODEL = "ggml-tiny.bin"

        // Пауза после стопа: даём сервису закрыть файловый поток
        private const val FILE_SETTLE_DELAY_MS = 700L
        private const val MIN_AUDIO_BYTES = 32000L
    }

    private val dao = AppDatabase.getDatabase(application).recordingDao()
    private val transcriber = WhisperTranscriber(application)
    private val textProcessor: TextProcessor = DefaultTextProcessor()

    // --- UI State для StreamingScreen ---
    private val _uiState = MutableStateFlow(TranscriptionState())
    val uiState: StateFlow<TranscriptionState> = _uiState.asStateFlow()

    // --- Список записей из БД ---
    val recordings: StateFlow<List<Recording>> = dao.getAllRecordings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    val allRecordings: StateFlow<List<Recording>>
        get() = recordings

    // --- Ошибки ---
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var audioCollectionJob: Job? = null
    private var finalizationJob: Job? = null
    private var settingsJob: Job? = null
    private var errorSubscriptionJob: Job? = null
    private var recordingStartedAt: Long = 0L
    private var lastDurationSeconds: Int = 0

    init {
        // Инициализация streaming-модели (tiny) с индикатором загрузки
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingModel = true)
            val success = transcriber.initialize(STREAMING_MODEL)
            _uiState.value = _uiState.value.copy(isLoadingModel = false)

            if (!success) {
                setError("Не удалось загрузить модель Whisper. Проверьте наличие ggml-tiny.bin в assets.")
                Log.e(TAG, "Failed to initialize Whisper model")
            } else {
                Log.i(TAG, "Streaming model initialized")
            }
        }

        // Подписка на изменения настроек
        settingsJob = viewModelScope.launch {
            settingsViewModel.settingsChanged.collect {
                if (!_uiState.value.isRecording && !_uiState.value.isFinalizing) {
                    Log.i(TAG, "Settings changed, reinitializing streaming model")
                    _uiState.value = _uiState.value.copy(isLoadingModel = true)
                    transcriber.initialize(STREAMING_MODEL)
                    _uiState.value = _uiState.value.copy(isLoadingModel = false)
                } else {
                    Log.w(TAG, "Settings changed but recording/finalization is active - changes will apply later")
                }
            }
        }

        // Подписка на ошибки от RecordingService
        errorSubscriptionJob = viewModelScope.launch {
            AudioChunkRepository.errors.collect { error ->
                if (error != null) {
                    setError(error)
                    Log.e(TAG, "Service error: $error")
                }
            }
        }
    }

    /**
     * Дублирует ошибку в оба потока: errorMessage (для будущих потребителей)
     * и uiState.error (то, что реально отображает StreamingScreen).
     */
    private fun setError(message: String) {
        _errorMessage.value = message
        _uiState.value = _uiState.value.copy(error = message)
    }

    private fun countWords(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        return trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
    }

    fun getRecordingById(id: Long): Flow<Recording?> {
        return recordings.map { list ->
            list.find { it.id == id }
        }
    }

    /**
     * Запуск записи через RecordingService + подписка на аудио-чанки.
     * Блокируется, если модель ещё грузится или идёт финализация.
     */
    fun startRecording() {
        if (_uiState.value.isRecording) return
        if (_uiState.value.isFinalizing) {
            setError("Дождитесь окончания финализации")
            return
        }
        if (_uiState.value.isLoadingModel) {
            setError("Модель ещё загружается, подождите")
            return
        }
        if (!transcriber.isReady()) {
            setError("Модель Whisper не готова. Перезапустите приложение.")
            return
        }

        _uiState.value = TranscriptionState(isRecording = true)
        recordingStartedAt = System.currentTimeMillis()
        lastDurationSeconds = 0

        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        context.startForegroundService(intent)

        // Live-транскрибация: best-effort. На медленных устройствах текст
        // появляется с большим запаздыванием — это нормально, полный текст
        // даст финализация после стопа.
        val currentLanguage = settingsViewModel.language.value

        audioCollectionJob = viewModelScope.launch {
            AudioChunkRepository.audioChunks.collect { chunk ->
                val rawText = transcriber.processChunk(chunk, currentLanguage)

                if (rawText.isNotBlank()) {
                    val processedText = textProcessor.applyVoiceCommands(rawText).trim()

                    if (processedText.isNotBlank()) {
                        val currentLive = _uiState.value.liveText
                        val newLive = if (currentLive.isEmpty()) {
                            processedText
                        } else {
                            "$currentLive $processedText"
                        }.trim()

                        _uiState.value = _uiState.value.copy(
                            liveText = newLive,
                            wordCount = countWords(newLive),
                            error = null
                        )
                    }
                }
            }
        }
    }

    /**
     * Остановка записи + финальная транскрибация всего файла.
     * Именно здесь пользователь получает текст на медленных устройствах.
     */
    fun stopRecording() {
        if (!_uiState.value.isRecording) return

        lastDurationSeconds = if (recordingStartedAt > 0L) {
            ((System.currentTimeMillis() - recordingStartedAt) / 1000L).toInt()
        } else {
            0
        }

        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)

        // Live-коллектор отменяем: очередь чанков всё равно не успевает,
        // полный текст даст processFile ниже.
        audioCollectionJob?.cancel()
        audioCollectionJob = null

        _uiState.value = _uiState.value.copy(isRecording = false, isFinalizing = true)

        finalizationJob = viewModelScope.launch {
            try {
                // Даём сервису время закрыть файловый поток
                delay(FILE_SETTLE_DELAY_MS)

                val audioFile = File(context.cacheDir, RECORDING_FILE)
                val language = settingsViewModel.language.value

                val fileText = if (audioFile.exists() && audioFile.length() > MIN_AUDIO_BYTES) {
                    Log.i(TAG, "Final transcription: ${audioFile.length()} bytes")
                    transcriber.processFile(audioFile.absolutePath, language)
                } else {
                    Log.w(TAG, "Audio file missing or too small: ${audioFile.length()}")
                    ""
                }

                val result = when {
                    fileText.isNotBlank() -> textProcessor.process(fileText)
                    _uiState.value.liveText.isNotBlank() -> textProcessor.process(_uiState.value.liveText)
                    else -> ""
                }

                _uiState.value = _uiState.value.copy(
                    isFinalizing = false,
                    finalizedText = result,
                    liveText = "",
                    wordCount = countWords(result)
                )

                if (result.isBlank()) {
                    setError("Не удалось распознать речь")
                } else {
                    Log.i(TAG, "Finalized: ${result.take(50)}...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Finalization failed", e)
                _uiState.value = _uiState.value.copy(isFinalizing = false)
                setError("Ошибка финализации: ${e.message}")
            }
        }
    }

    /**
     * Сохранение готового текста в БД.
     * Финализация уже выполнена на стопе — здесь только персист.
     */
    fun saveRecording() {
        if (_uiState.value.isRecording) {
            setError("Нельзя сохранить во время записи. Сначала остановите запись.")
            return
        }
        if (_uiState.value.isFinalizing) {
            setError("Дождитесь окончания финализации")
            return
        }

        val textToSave = _uiState.value.finalizedText.trim()
        if (textToSave.isBlank()) {
            setError("Нечего сохранять: текст пустой")
            return
        }

        viewModelScope.launch {
            try {
                val recording = Recording(
                    title = "Запись ${System.currentTimeMillis()}",
                    transcription = textToSave,
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = lastDurationSeconds
                )
                dao.insert(recording)
                Log.i(TAG, "Recording saved successfully")

                _uiState.value = TranscriptionState()
                recordingStartedAt = 0L
                lastDurationSeconds = 0
            } catch (e: Exception) {
                Log.e(TAG, "Error saving recording", e)
                setError("Ошибка сохранения: ${e.message}")
            }
        }
    }

    fun updateRecordingTitle(id: Long, newTitle: String) {
        viewModelScope.launch {
            try {
                val recordingsList = dao.getAllRecordings().firstOrNull()
                val recording = recordingsList?.find { it.id == id }

                if (recording == null) {
                    setError("Запись не найдена")
                    return@launch
                }

                dao.update(recording.copy(title = newTitle.trim()))
                Log.i(TAG, "Recording title updated: $id")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating recording title", e)
                setError("Ошибка переименования: ${e.message}")
            }
        }
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            try {
                dao.delete(recording)
                Log.i(TAG, "Recording deleted: ${recording.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting recording", e)
                setError("Ошибка удаления: ${e.message}")
            }
        }
    }

    fun deleteRecording(id: Long) {
        viewModelScope.launch {
            try {
                dao.deleteById(id)
                Log.i(TAG, "Recording deleted by id: $id")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting recording by id", e)
                setError("Ошибка удаления: ${e.message}")
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
        _uiState.value = _uiState.value.copy(error = null)
        AudioChunkRepository.clearError()
    }

    override fun onCleared() {
        super.onCleared()
        transcriber.shutdown()
        audioCollectionJob?.cancel()
        finalizationJob?.cancel()
        settingsJob?.cancel()
        errorSubscriptionJob?.cancel()
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