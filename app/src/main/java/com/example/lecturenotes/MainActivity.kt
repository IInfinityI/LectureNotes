package com.example.lecturenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.lecturenotes.ui.RecordingDetailScreen
import com.example.lecturenotes.ui.RecordingViewModel
import com.example.lecturenotes.ui.RecordingViewModelFactory
import com.example.lecturenotes.ui.RecordingsListScreen
import com.example.lecturenotes.ui.SettingsConstants
import com.example.lecturenotes.ui.SettingsScreen
import com.example.lecturenotes.ui.SettingsViewModel
import com.example.lecturenotes.ui.StreamingScreen
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

                    // SettingsViewModel создаётся первым (не имеет зависимостей)
                    val settingsViewModel: SettingsViewModel = viewModel()

                    // RecordingViewModel создаётся через фабрику с инъекцией SettingsViewModel
                    val application = this@MainActivity.application
                    val recordingViewModel: RecordingViewModel = viewModel(
                        factory = RecordingViewModelFactory(
                            application = application,
                            settingsViewModel = settingsViewModel
                        )
                    )

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
                                onSaveClick = { recordingViewModel.saveRecording() },
                                onSettingsClick = { navController.navigate("settings") },
                                onHistoryClick = { navController.navigate("history") },
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
                            RecordingsListScreen(
                                viewModel = recordingViewModel,
                                onNavigateToRecording = { recordingId ->
                                    navController.navigate("detail/$recordingId")
                                },
                                onNavigateToSettings = { navController.navigate("settings") },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(
                            route = "detail/{recordingId}",
                            arguments = listOf(
                                navArgument("recordingId") { type = NavType.LongType }
                            )
                        ) { backStackEntry ->
                            val recordingId = backStackEntry.arguments?.getLong("recordingId")
                                ?: return@composable
                            RecordingDetailScreen(
                                recordingId = recordingId,
                                viewModel = recordingViewModel,
                                onBack = { navController.popBackStack() },
                                onDelete = { id ->
                                    recordingViewModel.deleteRecording(id)
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}