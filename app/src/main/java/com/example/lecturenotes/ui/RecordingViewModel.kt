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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecordingViewModel(
    application: Application,
    private val settingsViewModel: SettingsViewModel
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RecordingViewModel"
        private const val RECORDING_FILE = "recording_live.pcm"
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
    private var settingsJob: Job? = null
    private var recordingStartedAt: Long = 0L
    private var lastDurationSeconds: Int = 0

    init {
        // Инициализация streaming-модели (tiny)
        viewModelScope.launch {
            val modelSize = settingsViewModel.modelSize.value
            val success = initializeStreamingModel(modelSize)
            if (!success) {
                _uiState.value = _uiState.value.copy(
                    error = "Не удалось загрузить модель Whisper"
                )
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
                    initializeStreamingModel(modelSize)
                } else {
                    Log.w(TAG, "Settings changed but recording is active - changes will apply after stop")
                }
            }
        }
    }

    /**
     * Инициализация streaming-модели (tiny) с учётом настроек.
     */
    private suspend fun initializeStreamingModel(modelSize: String): Boolean {
        // Для стриминга ВСЕГДА используем tiny, независимо от настроек
        // Настройки влияют только на финальную модель
        val streamingModelName = "ggml-tiny.bin"
        Log.i(TAG, "Initializing streaming model: $streamingModelName")
        return transcriber.initialize(streamingModelName)
    }

    fun getRecordingById(id: Long): kotlinx.coroutines.flow.Flow<Recording?> {
        return recordings.map { list ->
            list.find { it.id == id }
        }
    }

    /**
     * Запуск записи через RecordingService + подписка на аудио-чанки.
     */
    fun startRecording() {
        if (_uiState.value.isRecording) return

        _uiState.value = TranscriptionState(isRecording = true)
        recordingStartedAt = System.currentTimeMillis()
        lastDurationSeconds = 0

        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        context.startForegroundService(intent)

        // Читаем текущие настройки
        val currentLanguage = settingsViewModel.language.value

        audioCollectionJob = viewModelScope.launch {
            AudioChunkRepository.audioChunks.collect { chunk ->
                // Используем streaming-модель (tiny) для live-транскрибации
                val rawText = transcriber.processChunk(chunk, currentLanguage)

                if (rawText.isNotBlank()) {
                    // В live-режиме применяем только голосовые команды
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
                            wordCount = newLive.split("\\s+".toRegex())
                                .filter { it.isNotBlank() }
                                .size,
                            error = null
                        )
                    }
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

        audioCollectionJob?.cancel()
        audioCollectionJob = null

        // Переносим liveText в finalizedText (без финальной транскрибации)
        // Финальная транскрибация будет при сохранении
        val rawFinalText = _uiState.value.fullText
        val finalText = if (rawFinalText.isBlank()) {
            ""
        } else {
            textProcessor.process(rawFinalText)
        }

        _uiState.value = _uiState.value.copy(
            isRecording = false,
            isFinalizing = false,
            finalizedText = finalText,
            liveText = "",
            wordCount = finalText.split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                .size
        )
    }

    /**
     * Сохранение текущей транскрибации в БД.
     * Выполняет финальную транскрибацию через base-модель перед сохранением.
     */
    fun saveRecording() {
        if (_uiState.value.isRecording) {
            _errorMessage.value = "Нельзя сохранить во время записи. Сначала остановите запись."
            return
        }

        viewModelScope.launch {
            try {
                val currentLanguage = settingsViewModel.language.value
                val context = getApplication<Application>()
                val audioPath = File(context.cacheDir, RECORDING_FILE).absolutePath
                val audioFile = File(audioPath)

                // Проверяем, существует ли аудиофайл
                val hasAudioFile = audioFile.exists() && audioFile.length() > 32000

                val textToSave = if (hasAudioFile) {
                    // Финальная транскрибация через base-модель
                    Log.i(TAG, "Performing final transcription with base model...")
                    _uiState.value = _uiState.value.copy(isFinalizing = true)

                    val finalTranscription = transcriber.processFile(audioPath, currentLanguage)
                    
                    _uiState.value = _uiState.value.copy(isFinalizing = false)

                    if (finalTranscription.isNotBlank()) {
                        Log.i(TAG, "Final transcription successful: ${finalTranscription.take(50)}...")
                        textProcessor.process(finalTranscription)
                    } else {
                        // Fallback: используем live-текст если финальная транскрибация не удалась
                        Log.w(TAG, "Final transcription failed or empty, using live text")
                        val liveText = _uiState.value.finalizedText
                        if (liveText.isBlank()) {
                            _errorMessage.value = "Нечего сохранять: текст пустой"
                            return@launch
                        }
                        liveText
                    }
                } else {
                    // Нет аудиофайла — используем live-текст
                    Log.w(TAG, "Audio file not found or too small, using live text")
                    val liveText = _uiState.value.finalizedText
                    if (liveText.isBlank()) {
                        _errorMessage.value = "Нечего сохранять: текст пустой"
                        return@launch
                    }
                    liveText
                }

                if (textToSave.isBlank()) {
                    _errorMessage.value = "Нечего сохранять: текст пустой"
                    return@launch
                }

                val durationSeconds = lastDurationSeconds

                val recording = Recording(
                    title = "Запись ${System.currentTimeMillis()}",
                    transcription = textToSave.trim(),
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = durationSeconds
                )
                dao.insert(recording)
                Log.i(TAG, "Recording saved successfully")

                _uiState.value = TranscriptionState()
                recordingStartedAt = 0L
                lastDurationSeconds = 0
            } catch (e: Exception) {
                Log.e(TAG, "Error saving recording", e)
                _errorMessage.value = "Ошибка сохранения: ${e.message}"
                _uiState.value = _uiState.value.copy(isFinalizing = false)
            }
        }
    }

    fun updateRecordingTitle(id: Long, newTitle: String) {
        viewModelScope.launch {
            try {
                val recordingsList = dao.getAllRecordings().stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Lazily,
                    initialValue = emptyList()
                ).value
                val recording = recordingsList.find { it.id == id }

                if (recording == null) {
                    _errorMessage.value = "Запись не найдена"
                    return@launch
                }

                dao.update(recording.copy(title = newTitle.trim()))
                Log.i(TAG, "Recording title updated: $id")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating recording title", e)
                _errorMessage.value = "Ошибка переименования: ${e.message}"
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
                _errorMessage.value = "Ошибка удаления: ${e.message}"
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