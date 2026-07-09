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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    
    object PreferencesKeys {
        val MODEL_SIZE = stringPreferencesKey("model_size")
        val LANGUAGE = stringPreferencesKey("language")
    }

    private val dataStore = application.dataStore

    val modelSize: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MODEL_SIZE] ?: "tiny"
    }

    val language: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LANGUAGE] ?: "ru"
    }

    fun setModelSize(size: String) {
        viewModelScope.launch {
            dataStore.edit { preferences ->
                preferences[PreferencesKeys.MODEL_SIZE] = size
            }
        }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch {
            dataStore.edit { preferences ->
                preferences[PreferencesKeys.LANGUAGE] = lang
            }
        }
    }
}
