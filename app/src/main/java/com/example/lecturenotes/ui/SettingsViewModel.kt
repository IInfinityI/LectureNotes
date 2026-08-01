package com.example.lecturenotes.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "SettingsViewModel"
        
        // Допустимые значения для валидации
        val VALID_MODEL_SIZES = listOf("tiny", "base", "small", "medium", "large")
        val VALID_LANGUAGES = listOf("ru", "en", "uk", "de", "fr", "es", "zh", "ja", "ko")
        
        // Значения по умолчанию
        const val DEFAULT_MODEL_SIZE = "base"
        const val DEFAULT_LANGUAGE = "ru"
    }
    
    object PreferencesKeys {
        val MODEL_SIZE = stringPreferencesKey("model_size")
        val LANGUAGE = stringPreferencesKey("language")
    }
    
    private val dataStore = application.dataStore
    
    // Состояние загрузки
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading
    
    // Сообщения об ошибках
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage
    
    /**
     * Размер модели (Flow для реактивного чтения)
     */
    val modelSize: Flow<String> = dataStore.data.map { preferences ->
        try {
            val size = preferences[PreferencesKeys.MODEL_SIZE] ?: DEFAULT_MODEL_SIZE
            if (size in VALID_MODEL_SIZES) {
                size
            } else {
                Log.w(TAG, "Invalid model size: $size, using default: $DEFAULT_MODEL_SIZE")
                DEFAULT_MODEL_SIZE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading model size: ${e.message}", e)
            DEFAULT_MODEL_SIZE
        }
    }
    
    /**
     * Язык распознавания (Flow для реактивного чтения)
     */
    val language: Flow<String> = dataStore.data.map { preferences ->
        try {
            val lang = preferences[PreferencesKeys.LANGUAGE] ?: DEFAULT_LANGUAGE
            if (lang in VALID_LANGUAGES) {
                lang
            } else {
                Log.w(TAG, "Invalid language: $lang, using default: $DEFAULT_LANGUAGE")
                DEFAULT_LANGUAGE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading language: ${e.message}", e)
            DEFAULT_LANGUAGE
        }
    }
    
    /**
     * Установка размера модели
     */
    fun setModelSize(size: String) {
        if (size !in VALID_MODEL_SIZES) {
            Log.w(TAG, "Invalid model size: $size")
            _errorMessage.value = "Недопустимый размер модели: $size"
            return
        }
        
        viewModelScope.launch {
            try {
                _isLoading.value = true
                dataStore.edit { preferences ->
                    preferences[PreferencesKeys.MODEL_SIZE] = size
                }
                Log.i(TAG, "Model size set to: $size")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting model size: ${e.message}", e)
                _errorMessage.value = "Ошибка сохранения размера модели: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Установка языка распознавания
     */
    fun setLanguage(lang: String) {
        if (lang !in VALID_LANGUAGES) {
            Log.w(TAG, "Invalid language: $lang")
            _errorMessage.value = "Недопустимый язык: $lang"
            return
        }
        
        viewModelScope.launch {
            try {
                _isLoading.value = true
                dataStore.edit { preferences ->
                    preferences[PreferencesKeys.LANGUAGE] = lang
                }
                Log.i(TAG, "Language set to: $lang")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting language: ${e.message}", e)
                _errorMessage.value = "Ошибка сохранения языка: ${e.message}"
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
        Log.i(TAG, "SettingsViewModel cleared")
    }
}

/**
 * Factory для создания SettingsViewModel
 */
class SettingsViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}