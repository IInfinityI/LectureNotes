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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RecordingViewModel"
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

    // Контракт для RecordingsListScreen
    val allRecordings: StateFlow<List<Recording>>
        get() = recordings

    // --- Ошибки ---
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var audioCollectionJob: Job? = null
    private var recordingStartedAt: Long = 0L
    private var lastDurationSeconds: Int = 0

    init {
        viewModelScope.launch {
            val success = transcriber.initialize()
            if (!success) {
                _uiState.value = _uiState.value.copy(
                    error = "Не удалось загрузить модель Whisper"
                )
                Log.e(TAG, "Failed to initialize Whisper model")
            }
        }
    }

    /**
     * Контракт для RecordingDetailScreen.
     */
    fun getRecordingById(id: Long): Flow<Recording?> {
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

        audioCollectionJob = viewModelScope.launch {
            AudioChunkRepository.audioChunks.collect { chunk ->
                val rawText = transcriber.processChunk(chunk)

                if (rawText.isNotBlank()) {
                    // В live-режиме применяем только голосовые команды.
                    // Арифметика и финальная нормализация — при остановке.
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
     */
    fun saveRecording() {
        val finalized = _uiState.value.finalizedText
        val textToSave = if (finalized.isNotBlank()) {
            finalized
        } else {
            val rawText = _uiState.value.fullText
            if (rawText.isBlank()) {
                _errorMessage.value = "Нечего сохранять: текст пустой"
                return
            }
            textProcessor.process(rawText)
        }.trim()

        if (textToSave.isBlank()) {
            _errorMessage.value = "Нечего сохранять: текст пустой"
            return
        }

        val durationSeconds = if (_uiState.value.isRecording && recordingStartedAt > 0L) {
            ((System.currentTimeMillis() - recordingStartedAt) / 1000L).toInt()
        } else {
            lastDurationSeconds
        }

        viewModelScope.launch {
            try {
                val recording = Recording(
                    title = "Запись ${System.currentTimeMillis()}",
                    transcription = textToSave,
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
            }
        }
    }

    /**
     * Обновление названия записи.
     */
    fun updateRecordingTitle(id: Long, newTitle: String) {
        viewModelScope.launch {
            try {
                val recordingsList = dao.getAllRecordings().firstOrNull()
                val recording = recordingsList?.find { it.id == id }

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

    /**
     * Удаление записи по объекту.
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

    /**
     * Удаление записи по ID.
     */
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
        Log.i(TAG, "ViewModel cleared")
    }
}

class RecordingViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RecordingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RecordingViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}