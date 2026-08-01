package com.example.lecturenotes.ui

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Константы для настроек.
 * Вынесены отдельно, чтобы избежать дублирования и обеспечить единый источник правды.
 */
object SettingsConstants {
    /**
     * Доступные размеры моделей Whisper.
     * - tiny: ~75MB, быстрая, низкое качество
     * - base: ~150MB, баланс скорости и качества
     * - small: ~500MB, хорошее качество
     * - medium: ~1.5GB, высокое качество
     * - large: ~3GB, максимальное качество (требует много RAM)
     */
    val AVAILABLE_MODEL_SIZES = listOf("tiny", "base", "small", "medium", "large")
    
    /**
     * Доступные языки для распознавания.
     * "auto" — автоматическое определение (может быть медленнее)
     */
    val AVAILABLE_LANGUAGES = listOf(
        "auto" to "Автоопределение",
        "ru" to "Русский",
        "en" to "English",
        "uk" to "Українська",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español"
    )
    
    const val DEFAULT_MODEL_SIZE = "base"
    const val DEFAULT_LANGUAGE = "auto"
    
    // Ключи для DataStore
    val KEY_MODEL_SIZE = stringPreferencesKey("whisper_model_size")
    val KEY_LANGUAGE = stringPreferencesKey("whisper_language")
}

/**
 * DataStore для хранения настроек.
 */
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * ViewModel для управления настройками приложения.
 * 
 * ОТВЕТСТВЕННОСТЬ:
 * - Хранение и предоставление настроек Whisper (размер модели, язык)
 * - Валидация входных данных
 * - Уведомление других модулей об изменении настроек
 * 
 * КОНТРАКТЫ:
 * - modelSize: StateFlow<String> — текущий размер модели
 * - language: StateFlow<String> — текущий язык
 * - settingsChanged: Flow<Unit> — уведомление об изменении настроек
 * - setModelSize(size) — установить размер модели (с валидацией)
 * - setLanguage(lang) — установить язык (с валидацией)
 * - resetToDefaults() — сбросить настройки к дефолтным
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val dataStore = application.settingsDataStore
    
    // Текущий размер модели
    val modelSize: StateFlow<String> = dataStore.data
        .map { preferences ->
            val size = preferences[SettingsConstants.KEY_MODEL_SIZE] ?: SettingsConstants.DEFAULT_MODEL_SIZE
            // Валидация: если значение некорректное, возвращаем дефолт
            if (size in SettingsConstants.AVAILABLE_MODEL_SIZES) size else SettingsConstants.DEFAULT_MODEL_SIZE
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SettingsConstants.DEFAULT_MODEL_SIZE
        )
    
    // Текущий язык
    val language: StateFlow<String> = dataStore.data
        .map { preferences ->
            val lang = preferences[SettingsConstants.KEY_LANGUAGE] ?: SettingsConstants.DEFAULT_LANGUAGE
            // Валидация: если значение некорректное, возвращаем дефолт
            val validCodes = SettingsConstants.AVAILABLE_LANGUAGES.map { it.first }
            if (lang in validCodes) lang else SettingsConstants.DEFAULT_LANGUAGE
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SettingsConstants.DEFAULT_LANGUAGE
        )
    
    // Состояние операции сохранения настроек
    private val _saveState = MutableStateFlow<SettingsSaveState>(SettingsSaveState.Idle)
    val saveState: StateFlow<SettingsSaveState> = _saveState.asStateFlow()
    
    // Уведомление об изменении настроек (для других модулей)
    private val _settingsChanged = MutableStateFlow(0L) // timestamp последнего изменения
    val settingsChanged: Flow<Long> = _settingsChanged.asStateFlow()
    
    /**
     * Установить размер модели Whisper.
     * 
     * @param size Один из: "tiny", "base", "small", "medium", "large"
     * @throws IllegalArgumentException если размер некорректный
     */
    fun setModelSize(size: String) {
        if (size !in SettingsConstants.AVAILABLE_MODEL_SIZES) {
            _saveState.value = SettingsSaveState.Error(
                "Некорректный размер модели: $size. Доступные: ${SettingsConstants.AVAILABLE_MODEL_SIZES.joinToString()}"
            )
            return
        }
        
        viewModelScope.launch {
            _saveState.value = SettingsSaveState.Saving
            try {
                dataStore.edit { preferences ->
                    preferences[SettingsConstants.KEY_MODEL_SIZE] = size
                }
                _saveState.value = SettingsSaveState.Success
                _settingsChanged.value = System.currentTimeMillis()
                
                // Сбрасываем состояние через 2 секунды
                kotlinx.coroutines.delay(2000)
                _saveState.value = SettingsSaveState.Idle
            } catch (e: Exception) {
                _saveState.value = SettingsSaveState.Error(
                    "Ошибка сохранения: ${e.localizedMessage ?: "неизвестная ошибка"}"
                )
            }
        }
    }
    
    /**
     * Установить язык распознавания.
     * 
     * @param language Код языка (например, "ru", "en", "auto")
     * @throws IllegalArgumentException если язык некорректный
     */
    fun setLanguage(language: String) {
        val validCodes = SettingsConstants.AVAILABLE_LANGUAGES.map { it.first }
        if (language !in validCodes) {
            _saveState.value = SettingsSaveState.Error(
                "Некорректный язык: $language. Доступные: ${validCodes.joinToString()}"
            )
            return
        }
        
        viewModelScope.launch {
            _saveState.value = SettingsSaveState.Saving
            try {
                dataStore.edit { preferences ->
                    preferences[SettingsConstants.KEY_LANGUAGE] = language
                }
                _saveState.value = SettingsSaveState.Success
                _settingsChanged.value = System.currentTimeMillis()
                
                // Сбрасываем состояние через 2 секунды
                kotlinx.coroutines.delay(2000)
                _saveState.value = SettingsSaveState.Idle
            } catch (e: Exception) {
                _saveState.value = SettingsSaveState.Error(
                    "Ошибка сохранения: ${e.localizedMessage ?: "неизвестная ошибка"}"
                )
            }
        }
    }
    
    /**
     * Сбросить настройки к дефолтным значениям.
     */
    fun resetToDefaults() {
        viewModelScope.launch {
            _saveState.value = SettingsSaveState.Saving
            try {
                dataStore.edit { preferences ->
                    preferences[SettingsConstants.KEY_MODEL_SIZE] = SettingsConstants.DEFAULT_MODEL_SIZE
                    preferences[SettingsConstants.KEY_LANGUAGE] = SettingsConstants.DEFAULT_LANGUAGE
                }
                _saveState.value = SettingsSaveState.Success
                _settingsChanged.value = System.currentTimeMillis()
                
                kotlinx.coroutines.delay(2000)
                _saveState.value = SettingsSaveState.Idle
            } catch (e: Exception) {
                _saveState.value = SettingsSaveState.Error(
                    "Ошибка сброса: ${e.localizedMessage ?: "неизвестная ошибка"}"
                )
            }
        }
    }
    
    /**
     * Сбросить состояние ошибки.
     */
    fun clearError() {
        _saveState.value = SettingsSaveState.Idle
    }
    
    /**
     * Получить человекочитаемое название языка по коду.
     */
    fun getLanguageDisplayName(code: String): String {
        return SettingsConstants.AVAILABLE_LANGUAGES
            .find { it.first == code }
            ?.second ?: code
    }
}

/**
 * Состояние операции сохранения настроек.
 */
sealed class SettingsSaveState {
    data object Idle : SettingsSaveState()
    data object Saving : SettingsSaveState()
    data object Success : SettingsSaveState()
    data class Error(val message: String) : SettingsSaveState()
}