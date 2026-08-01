package com.example.lecturenotes

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.lecturenotes.data.AppDatabase
import com.example.lecturenotes.data.Recording
import com.example.lecturenotes.transcription.WhisperTranscriber
import com.example.lecturenotes.ui.SettingsScreen
import com.example.lecturenotes.ui.StreamingScreen
import com.example.lecturenotes.ui.dataStore
import com.example.lecturenotes.ui.theme.LectureNotesTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private val streamingViewModel: StreamingViewModel by viewModels {
        StreamingViewModelFactory(application)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LectureNotesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(streamingViewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(viewModel: StreamingViewModel) {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = "streaming") {
        composable("streaming") {
            val isRecording by viewModel.isRecording.collectAsState()
            val liveText by viewModel.liveText.collectAsState()
            val isFinalizing by viewModel.isFinalizing.collectAsState()
            
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Запись лекции") },
                        actions = {
                            IconButton(onClick = { navController.navigate("settings") }) {
                                Icon(Icons.Default.Settings, contentDescription = "Настройки")
                            }
                        }
                    )
                }
            ) { padding ->
                StreamingScreen(
                    onBack = { },
                    isRecording = isRecording,
                    liveText = liveText,
                    isFinalizing = isFinalizing,
                    onToggleRecording = { viewModel.toggleRecording() }
                )
            }
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}

class StreamingViewModel(application: Application) : AndroidViewModel(application) {
    
    private val whisperTranscriber = WhisperTranscriber(application)
    private val dao = AppDatabase.getDatabase(application).recordingDao()
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()
    
    private val _isFinalizing = MutableStateFlow(false)
    val isFinalizing: StateFlow<Boolean> = _isFinalizing.asStateFlow()
    
    private var recordingJob: Job? = null
    
    private val LANGUAGE_KEY = androidx.datastore.preferences.core.stringPreferencesKey("language")

    init {
        viewModelScope.launch {
            val success = whisperTranscriber.initialize()
            if (!success) {
                _liveText.value = "Ошибка: модель не загружена"
            }
        }
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val startIntent = Intent(getApplication(), RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        ContextCompat.startForegroundService(getApplication(), startIntent)
        
        _isRecording.value = true
        _liveText.value = "Слушаю..."
        
        recordingJob = viewModelScope.launch {
            val language = getApplication<Application>().dataStore.data.map { prefs ->
                prefs[LANGUAGE_KEY] ?: "ru"
            }.first()
            
            RecordingService.audioChunks.collect { chunk ->
                val text = whisperTranscriber.processChunk(chunk, language)
                if (text.isNotEmpty()) {
                    val current = _liveText.value
                    _liveText.value = if (current == "Слушаю...") text else "$current $text"
                }
            }
        }
    }

    private fun stopRecording() {
        val stopIntent = Intent(getApplication(), RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        getApplication<Application>().startService(stopIntent)
        
        recordingJob?.cancel()
        _isRecording.value = false
        _isFinalizing.value = true
        
        viewModelScope.launch {
            val text = _liveText.value.trim()
            if (text.isNotEmpty() && text != "Слушаю...") {
                val recording = Recording(
                    title = "Запись ${System.currentTimeMillis()}",
                    transcription = text,
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = 0,
                    audioPath = null  // TODO: получить путь из RecordingService
                )
                dao.insert(recording)
            }
            _liveText.value = ""
            _isFinalizing.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        whisperTranscriber.shutdown()
    }
}

class StreamingViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StreamingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StreamingViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}