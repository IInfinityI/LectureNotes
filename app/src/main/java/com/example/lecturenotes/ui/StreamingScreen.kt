package com.example.lecturenotes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Состояние UI для экрана потоковой записи.
 * 
 * СОСТОЯНИЯ:
 * - Idle: не записываем, показываем кнопку "Начать запись"
 * - Listening: слушаем микрофон, но ещё не записываем
 * - Recording: записываем и распознаём речь, показываем текст
 * - Finalizing: финализируем запись (Whisper дообрабатывает последний блок)
 * - Error: произошла ошибка
 */
sealed class StreamingUiState {
    data object Idle : StreamingUiState()
    data object Listening : StreamingUiState()
    data class Recording(val text: String, val durationSeconds: Int = 0) : StreamingUiState()
    data class Finalizing(val text: String, val progress: Float) : StreamingUiState()
    data class Error(val message: String) : StreamingUiState()
}

/**
 * Экран потоковой записи и распознавания речи.
 * 
 * КОНТРАКТ:
 * @param uiState Текущее состояние UI (sealed class StreamingUiState)
 * @param onSaveClick Вызывается при нажатии кнопки "Сохранить запись"
 * @param onSettingsClick Вызывается при нажатии кнопки настроек
 * @param onBackClick Вызывается при нажатии кнопки "Назад"
 * @param onStartRecordingClick Вызывается при нажатии "Начать запись" (в состоянии Idle)
 * @param onStopRecordingClick Вызывается при нажатии "Остановить запись" (в состоянии Recording)
 * 
 * ОТВЕТСТВЕННОСТЬ:
 * - Отображение текущего состояния записи
 * - Показ распознанного текста в реальном времени
 * - Индикация прогресса финализации
 * - Предоставление кнопок управления записью
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingScreen(
    uiState: StreamingUiState,
    onSaveClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
    onStartRecordingClick: () -> Unit = {},
    onStopRecordingClick: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Запись лекции") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            // Нижняя панель с кнопками управления
            StreamingBottomBar(
                uiState = uiState,
                onSaveClick = onSaveClick,
                onStartClick = onStartRecordingClick,
                onStopClick = onStopRecordingClick
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (uiState) {
                is StreamingUiState.Idle -> IdleContent()
                is StreamingUiState.Listening -> ListeningContent()
                is StreamingUiState.Recording -> RecordingContent(
                    text = uiState.text,
                    durationSeconds = uiState.durationSeconds
                )
                is StreamingUiState.Finalizing -> FinalizingContent(
                    text = uiState.text,
                    progress = uiState.progress
                )
                is StreamingUiState.Error -> ErrorContent(message = uiState.message)
            }
        }
    }
}

/**
 * Состояние "Idle" — не записываем.
 */
@Composable
private fun IdleContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Нажмите \"Начать запись\" для старта",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Состояние "Listening" — слушаем микрофон.
 */
@Composable
private fun ListeningContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Анимированный индикатор прослушивания
        val pulseScale by animateFloatAsState(
            targetValue = 1.2f,
            animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
            label = "pulse"
        )
        
        Surface(
            modifier = Modifier
                .size(100.dp)
                .scale(pulseScale),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Слушаю",
                    modifier = Modifier.size(50.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Слушаю микрофон...",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Говорите, текст появится ниже",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Состояние "Recording" — записываем и распознаём речь.
 */
@Composable
private fun RecordingContent(text: String, durationSeconds: Int) {
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // Индикатор записи
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Запись",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Text(
                text = formatDuration(durationSeconds),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Распознанный текст
        if (text.isBlank()) {
            Text(
                text = "Ожидание речи...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

/**
 * Состояние "Finalizing" — финализируем запись.
 */
@Composable
private fun FinalizingContent(text: String, progress: Float) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Финализация записи...",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Показываем финальный текст
        if (text.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

/**
 * Состояние "Error" — произошла ошибка.
 */
@Composable
private fun ErrorContent(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Ошибка",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Нижняя панель с кнопками управления записью.
 */
@Composable
private fun StreamingBottomBar(
    uiState: StreamingUiState,
    onSaveClick: () -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (uiState) {
                is StreamingUiState.Idle -> {
                    Button(onClick = onStartClick) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Начать запись")
                    }
                }
                
                is StreamingUiState.Listening,
                is StreamingUiState.Recording -> {
                    Button(
                        onClick = onStopClick,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Остановить")
                    }
                }
                
                is StreamingUiState.Finalizing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Финализация...")
                }
                
                is StreamingUiState.Error -> {
                    Button(onClick = onStartClick) {
                        Text("Попробовать снова")
                    }
                }
            }
            
            // Кнопка сохранения (доступна только после финализации)
            AnimatedVisibility(
                visible = uiState is StreamingUiState.Finalizing,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Button(
                    onClick = onSaveClick,
                    enabled = uiState is StreamingUiState.Finalizing
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Сохранить")
                }
            }
        }
    }
}

/**
 * Форматирование длительности в секундах в строку "MM:SS".
 */
private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", minutes, secs)
}