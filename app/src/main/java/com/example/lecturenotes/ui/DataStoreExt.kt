package com.example.lecturenotes.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Extension property для доступа к DataStore из любого Context.
 * Создаётся один раз на процесс (singleton by delegate).
 */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lecture_notes_settings")