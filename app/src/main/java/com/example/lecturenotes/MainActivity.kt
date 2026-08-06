package com.example.lecturenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.lecturenotes.ui.StreamingScreen
import com.example.lecturenotes.ui.SettingsScreen
import com.example.lecturenotes.ui.RecordingViewModel
import com.example.lecturenotes.ui.SettingsViewModel
import com.example.lecturenotes.ui.SettingsConstants
import com.example.lecturenotes.ui.theme.LectureNotesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LectureNotesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val recordingViewModel = androidx.lifecycle.viewmodel.compose.viewModel<RecordingViewModel>()
                    val settingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel<SettingsViewModel>()

                    NavHost(
                        navController = navController,
                        startDestination = "streaming"
                    ) {
                        composable("streaming") {
                            val uiState by recordingViewModel.uiState.collectAsState()
                            StreamingScreen(
                                uiState = uiState,
                                onStartClick = { recordingViewModel.startRecording() },
                                onStopClick = { recordingViewModel.stopRecording() },
                                onSaveClick = {
                                    recordingViewModel.saveRecording()
                                },
                                onSettingsClick = { navController.navigate("settings") },
                                onBackClick = { finish() }
                            )
                        }
                        composable("settings") {
                            val currentModelSize by settingsViewModel.modelSize.collectAsState()
                            val currentLanguage by settingsViewModel.language.collectAsState()
                            SettingsScreen(
                                currentModelSize = currentModelSize,
                                currentLanguage = currentLanguage,
                                availableModelSizes = SettingsConstants.AVAILABLE_MODEL_SIZES,
                                availableLanguages = SettingsConstants.AVAILABLE_LANGUAGES,
                                onModelSizeSelected = { settingsViewModel.setModelSize(it) },
                                onLanguageSelected = { settingsViewModel.setLanguage(it) },
                                onResetToDefaults = { settingsViewModel.resetToDefaults() },
                                onBackClick = { navController.popBackStack() }
                            )
                        }
                        composable("history") {
                            Text("History Screen - TODO")
                        }
                    }
                }
            }
        }
    }
}