package com.example.lecturenotes.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.lecturenotes.data.AppDatabase
import com.example.lecturenotes.data.Recording
import kotlinx.coroutines.launch

class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "RecordingViewModel"
    }
    
    private val dao = AppDatabase.getDatabase(application).recordingDao()
    
    // Список всех записей
    val recordings: LiveData<List<Recording>> = dao.getAllRecordings().asLiveData()
    
    // Состояние загрузки
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading
    
    // Сообщения об ошибках
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage
    
    /**
     * Добавление новой записи в БД
     */
    fun addRecording(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "Cannot add empty recording")
            _errorMessage.value = "Текст записи не может быть пустым"
            return
        }
        
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val recording = Recording(
                    title = "Запись ${System.currentTimeMillis()}",
                    transcription = text.trim()
                )
                dao.insert(recording)
                Log.i(TAG, "Recording added successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding recording: ${e.message}", e)
                _errorMessage.value = "Ошибка сохранения записи: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Удаление записи из БД
     */
    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                dao.delete(recording)
                Log.i(TAG, "Recording deleted: ${recording.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting recording: ${e.message}", e)
                _errorMessage.value = "Ошибка удаления записи: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Обновление текста записи
     */
    fun updateRecording(recording: Recording, newText: String) {
        if (newText.isBlank()) {
            Log.w(TAG, "Cannot update with empty text")
            _errorMessage.value = "Текст записи не может быть пустым"
            return
        }
        
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val updatedRecording = recording.copy(transcription = newText.trim())
                dao.update(updatedRecording)
                Log.i(TAG, "Recording updated: ${recording.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating recording: ${e.message}", e)
                _errorMessage.value = "Ошибка обновления записи: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Очистка сообщения об ошибке
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "ViewModel cleared")
    }
}

/**
 * Factory для создания RecordingViewModel
 */
class RecordingViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RecordingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RecordingViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}