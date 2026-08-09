package com.example.lecturenotes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.lecturenotes.ui.RecordingViewModel
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
                    AppRoot()
                }
            }
        }
    }
}

/**
 * Корень приложения.
 * Сначала проверяет RECORD_AUDIO и запрашивает его при отсутствии.
 * Навигация показывается ТОЛЬКО после получения разрешения:
 * без него RecordingService не сможет ни стартовать (SecurityException
 * на Android 11+ для FGS типа microphone), ни создать AudioRecord.
 */
@Composable
private fun AppRoot() {
    val context = LocalContext.current

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasAudioPermission = results[Manifest.permission.RECORD_AUDIO] == true
    }

    // Автозапрос при первом запуске
    LaunchedEffect(Unit) {
        if (!hasAudioPermission) {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    if (hasAudioPermission) {
        AppNavHost()
    } else {
        PermissionGate(
            onRequest = { permissionLauncher.launch(requiredPermissions()) }
        )
    }
}

/**
 * Список разрешений: RECORD_AUDIO всегда, POST_NOTIFICATIONS на Android 13+.
 */
private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

/**
 * Экран-заглушка, пока нет разрешения на микрофон.
 */
@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Нет разрешения на запись аудио",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Для записи и распознавания речи нужен доступ к микрофону. Без него запись не работает.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequest) {
            Text("Выдать разрешение")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Если диалог не появляется — включите микрофон вручную: Настройки → Приложения → Lecture Notes → Разрешения.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Навигация. Логика не изменена, вынесена в отдельный composable.
 */
@Composable
private fun AppNavHost() {
    val activity = LocalContext.current as? ComponentActivity
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
                onBackClick = { activity?.finish() }
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