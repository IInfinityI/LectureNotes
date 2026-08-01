package com.example.lecturenotes.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lecturenotes.data.AppDatabase
import com.example.lecturenotes.data.Recording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Состояние UI для списка записей.
 */
sealed class RecordingsUiState {
    data object Loading : RecordingsUiState()
    data class Success(val recordings: List<Recording>) : RecordingsUiState()
    data class Error(val message: String) : RecordingsUiState()
}

/**
 * ViewModel для управления записями лекций.
 * 
 * ОТВЕТСТВЕННОСТЬ:
 * - Получение списка записей из БД
 * - Добавление новых записей
 * - Обновление и удаление записей
 * - Управление состоянием UI (загрузка/успех/ошибка)
 * 
 * КОНТРАКТЫ:
 * - allRecordings: StateFlow<RecordingsUiState> — текущее состояние списка
 * - addRecording(transcription, durationSeconds, audioPath) — сохранить новую запись
 * - updateRecording(recording) — обновить существующую
 * - deleteRecording(recording) — удалить запись
 * - clearError() — сбросить состояние ошибки
 */
class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    
    private val dao = AppDatabase.getDatabase(application).recordingDao()
    
    // Состояние UI для списка записей
    private val _uiState = MutableStateFlow<RecordingsUiState>(RecordingsUiState.Loading)
    val uiState: StateFlow<RecordingsUiState> = _uiState.asStateFlow()
    
    // Сырой Flow из БД (для внутреннего использования)
    val allRecordings: StateFlow<List<Recording>> = dao.getAllRecordings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly, // Немедленная подписка для актуальности
            initialValue = emptyList()
        )
    
    // Состояние операции сохранения
    private val _saveOperationState = MutableStateFlow<SaveOperationState>(SaveOperationState.Idle)
    val saveOperationState: StateFlow<SaveOperationState> = _saveOperationState.asStateFlow()
    
    init {
        // Загружаем записи при создании ViewModel
        loadRecordings()
    }
    
    /**
     * Загрузка списка записей из БД.
     * Обновляет _uiState в зависимости от результата.
     */
    private fun loadRecordings() {
        viewModelScope.launch {
            _uiState.value = RecordingsUiState.Loading
            try {
                allRecordings.collect { recordings ->
                    _uiState.value = RecordingsUiState.Success(recordings)
                }
            } catch (e: Exception) {
                _uiState.value = RecordingsUiState.Error(
                    message = "Ошибка загрузки записей: ${e.localizedMessage ?: "неизвестная ошибка"}"
                )
            }
        }
    }
    
    /**
     * Сохранение новой записи в БД.
     * 
     * @param transcription Распознанный текст
     * @param durationSeconds Длительность записи в секундах (0 если неизвестно)
     * @param audioPath Путь к аудиофайлу (null если не сохранён)
     */
    fun addRecording(
        transcription: String,
        durationSeconds: Int = 0,
        audioPath: String? = null
    ) {
        if (transcription.isBlank()) {
            _saveOperationState.value = SaveOperationState.Error("Транскрипция пуста")
            return
        }
        
        viewModelScope.launch {
            _saveOperationState.value = SaveOperationState.Saving
            try {
                val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                val title = "Запись ${dateFormat.format(Date())}"
                
                val recording = Recording(
                    title = title,
                    transcription = transcription.trim(),
                    durationSeconds = durationSeconds,
                    audioPath = audioPath
                )
                
                val id = dao.insert(recording)
                
                if (id > 0) {
                    _saveOperationState.value = SaveOperationState.Success
                    // Сбрасываем состояние через 2 секунды
                    kotlinx.coroutines.delay(2000)
                    _saveOperationState.value = SaveOperationState.Idle
                } else {
                    _saveOperationState.value = SaveOperationState.Error("Не удалось сохранить запись")
                }
            } catch (e: Exception) {
                _saveOperationState.value = SaveOperationState.Error(
                    message = e.localizedMessage ?: "Ошибка сохранения"
                )
            }
        }
    }
    
    /**
     * Обновление существующей записи.
     */
    fun updateRecording(recording: Recording) {
        viewModelScope.launch {
            try {
                dao.update(recording)
            } catch (e: Exception) {
                _saveOperationState.value = SaveOperationState.Error(
                    message = "Ошибка обновления: ${e.localizedMessage}"
                )
            }
        }
    }
    
    /**
     * Удаление записи.
     */
    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            try {
                dao.delete(recording)
            } catch (e: Exception) {
                _saveOperationState.value = SaveOperationState.Error(
                    message = "Ошибка удаления: ${e.localizedMessage}"
                )
            }
        }
    }
    
    /**
     * Сброс состояния ошибки.
     */
    fun clearError() {
        _saveOperationState.value = SaveOperationState.Idle
    }
    
    /**
     * Получение записи по ID (для редактирования).
     */
    fun getRecordingById(id: Long): Recording? {
        return (uiState.value as? RecordingsUiState.Success)
            ?.recordings
            ?.find { it.id == id }
    }
}

/**
 * Состояние операции сохранения.
 */
sealed class SaveOperationState {
    data object Idle : SaveOperationState()
    data object Saving : SaveOperationState()
    data object Success : SaveOperationState()
    data class Error(val message: String) : SaveOperationState()
}