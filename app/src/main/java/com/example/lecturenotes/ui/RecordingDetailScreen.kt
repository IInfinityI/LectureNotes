package com.example.lecturenotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.lecturenotes.data.Recording
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
    val recordings by viewModel.allRecordings.collectAsState()
    val recording = recordings.find { it.id == recordingId }

    if (recording == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    var isEditing by remember { mutableStateOf(false) }
    var editTitle by remember(recording.id) { mutableStateOf(recording.title) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Детали записи") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { isEditing = !isEditing }) {
                        Icon(Icons.Default.Edit, contentDescription = "Редактировать")
                    }
                    IconButton(onClick = { onDelete(recording.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить")
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
                .verticalScroll(rememberScrollState())
        ) {
            if (isEditing) {
                OutlinedTextField(
                    value = editTitle,
                    onValueChange = { editTitle = it },
                    label = { Text("Название") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.updateRecordingTitle(recording.id, editTitle)
                            isEditing = false
                        }
                    ) {
                        Text("Сохранить")
                    }
                    OutlinedButton(onClick = {
                        editTitle = recording.title
                        isEditing = false
                    }) {
                        Text("Отмена")
                    }
                }
            } else {
                Text(
                    text = recording.title.ifEmpty { "Без названия" },
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
                    .format(Date(recording.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (recording.durationSeconds > 0) {
                Text(
                    text = "Длительность: ${formatDuration(recording.durationSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Divider()

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = recording.transcription.ifEmpty { "Нет транскрибации" },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
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