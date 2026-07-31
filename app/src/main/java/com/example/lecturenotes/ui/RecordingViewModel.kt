package com.example.lecturenotes.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lecturenotes.data.AppDatabase
import com.example.lecturenotes.data.Recording
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).recordingDao()

    val allRecordings: StateFlow<List<Recording>> = dao.getAllRecordings()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun addRecording(transcription: String) {
        viewModelScope.launch {
            val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
            val title = "Запись ${dateFormat.format(Date())}"
            dao.insert(Recording(title = title, transcription = transcription))
        }
    }

    fun updateRecording(recording: Recording) {
        viewModelScope.launch {
            dao.update(recording)
        }
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            dao.delete(recording)
        }
    }
}