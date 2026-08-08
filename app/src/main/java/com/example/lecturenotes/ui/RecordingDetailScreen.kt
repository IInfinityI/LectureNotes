package com.example.lecturenotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lecturenotes.data.Recording
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    recordingId: Long,
    viewModel: RecordingViewModel,
    onBack: () -> Unit,
    onDelete: (Long) -> Unit
) {
    val recording by viewModel.getRecordingById(recordingId).collectAsStateWithLifecycle(null)
    val clipboardManager = LocalClipboardManager.current
    var isEditingTitle by remember { mutableStateOf(false) }
    var newTitle by remember { recording?.title ?: "" } { mutableStateOf(recording?.title ?: "") }

    LaunchedEffect(recordingId) {
        newTitle = recording?.title ?: ""
    }

    recording?.let { rec ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        if (isEditingTitle) {
                            TextField(
                                value = newTitle,
                                onValueChange = { newTitle = it },
                                label = { Text("Название") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = rec.title.ifEmpty { "Без названия" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    actions = {
                        if (!isEditingTitle) {
                            IconButton(onClick = { isEditingTitle = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Редактировать")
                            }
                            IconButton(onClick = { 
                                clipboardManager.setText(AnnotatedString(rec.transcription)) 
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Копировать")
                            }
                            IconButton(onClick = { 
                                exportRecordingToFile(rec) 
                            }) {
                                Icon(Icons.Default.Share, contentDescription = "Экспортировать")
                            }
                            IconButton(onClick = { onDelete(rec.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить")
                            }
                        } else {
                            IconButton(onClick = { 
                                viewModel.updateRecordingTitle(rec.id, newTitle)
                                isEditingTitle = false
                            }) {
                                Icon(Icons.Default.Check, contentDescription = "Сохранить")
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
            ) {
                // Metadata Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Метаданные",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        InfoRow(label = "Дата", value = formatDate(rec.timestamp))
                        InfoRow(label = "Длительность", value = formatDuration(rec.durationSeconds))
                    }
                }

                // Transcription Content
                SelectionContainer {
                    Text(
                        text = rec.transcription.ifEmpty { "Транскрипция отсутствует" },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    } ?: run {
        // Loading state
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        .format(Date(timestamp))
}

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    
    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
        else -> String.format("%d:%02d", minutes, secs)
    }
}

private fun exportRecordingToFile(recording: Recording) {
    // TODO: Implement actual file export logic here
    // This would typically involve creating a file picker or saving to Downloads folder
    // For now, this is a placeholder indicating where the logic goes
}