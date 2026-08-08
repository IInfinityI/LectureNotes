package com.example.lecturenotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lecturenotes.transcription.TranscriptionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingScreen(
    uiState: TranscriptionState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSaveClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lecture Notes") },
                actions = {
                    IconButton(onClick = onHistoryClick) {
                        Icon(Icons.Filled.List, contentDescription = "История записей")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Status indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (uiState.isRecording) Color.Red
                                else if (uiState.isFinalizing) Color.Yellow
                                else Color.Gray
                            )
                    )
                    Text(
                        text = when {
                            uiState.isFinalizing -> "Финализация..."
                            uiState.isRecording -> "Запись..."
                            else -> "Готов"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "${uiState.wordCount} слов",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            // Live transcription area
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    if (uiState.error != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Error,
                                contentDescription = "Ошибка",
                                tint = Color.Red,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = uiState.error,
                                color = Color.Red,
                                fontSize = 14.sp
                            )
                        }
                    }

                    if (uiState.liveText.isEmpty() && uiState.finalizedText.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Нажмите кнопку микрофона, чтобы начать запись",
                                color = Color.Gray,
                                fontSize = 16.sp
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (uiState.finalizedText.isNotEmpty()) {
                                Text(
                                    text = uiState.finalizedText,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            if (uiState.liveText.isNotEmpty()) {
                                Text(
                                    text = uiState.liveText,
                                    fontSize = 16.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                if (uiState.isRecording || uiState.isFinalizing) {
                    Button(
                        onClick = onStopClick,
                        enabled = !uiState.isFinalizing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red
                        )
                    ) {
                        Icon(Icons.Outlined.Stop, contentDescription = "Стоп")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Стоп")
                    }
                } else {
                    Button(
                        onClick = onStartClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Outlined.Mic, contentDescription = "Запись")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Запись")
                    }
                }

                Button(
                    onClick = onSaveClick,
                    enabled = uiState.finalizedText.isNotEmpty() || uiState.liveText.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(Icons.Filled.Save, contentDescription = "Сохранить")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Сохранить")
                }
            }
        }
    }
}