package com.example.lecturenotes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Экран настроек приложения.
 * 
 * КОНТРАКТ:
 * @param currentModelSize Текущий размер модели Whisper
 * @param currentLanguage Текущий язык распознавания
 * @param availableModelSizes Список доступных размеров моделей
 * @param availableLanguages Список доступных языков (Pair<код, название>)
 * @param onModelSizeSelected Вызывается при выборе нового размера модели
 * @param onLanguageSelected Вызывается при выборе нового языка
 * @param onResetToDefaults Вызывается при нажатии "Сбросить настройки"
 * @param onBackClick Вызывается при нажатии кнопки "Назад"
 * 
 * ОТВЕТСТВЕННОСТЬ:
 * - Отображение текущих настроек
 * - Предоставление диалогов для выбора размера модели и языка
 * - Валидация выбора
 * - Сброс настроек к дефолтным
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentModelSize: String,
    currentLanguage: String,
    availableModelSizes: List<String>,
    availableLanguages: List<Pair<String, String>>,
    onModelSizeSelected: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onResetToDefaults: () -> Unit,
    onBackClick: () -> Unit
) {
    // Состояние диалогов
    var showModelSizeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showResetConfirmationDialog by remember { mutableStateOf(false) }
    
    // Локальное состояние выбора (для диалогов)
    var selectedModelSize by remember(currentModelSize) { mutableStateOf(currentModelSize) }
    var selectedLanguage by remember(currentLanguage) { mutableStateOf(currentLanguage) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Секция: Модель Whisper
            SettingsSection(title = "Модель Whisper") {
                SettingsItem(
                    title = "Размер модели",
                    subtitle = getModelSizeDescription(currentModelSize),
                    value = currentModelSize,
                    onClick = {
                        selectedModelSize = currentModelSize
                        showModelSizeDialog = true
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Секция: Язык распознавания
            SettingsSection(title = "Язык") {
                SettingsItem(
                    title = "Язык распознавания",
                    subtitle = "Выберите язык для распознавания речи",
                    value = availableLanguages.find { it.first == currentLanguage }?.second ?: currentLanguage,
                    onClick = {
                        selectedLanguage = currentLanguage
                        showLanguageDialog = true
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Секция: Сброс настроек
            SettingsSection(title = "Сброс") {
                Button(
                    onClick = { showResetConfirmationDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Сбросить все настройки")
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Информационная карточка
            InfoCard()
        }
    }
    
    // Диалог выбора размера модели
    AnimatedVisibility(
        visible = showModelSizeDialog,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        if (showModelSizeDialog) {
            ModelSizeSelectionDialog(
                availableSizes = availableModelSizes,
                selectedSize = selectedModelSize,
                onSizeSelected = { selectedModelSize = it },
                onConfirm = {
                    onModelSizeSelected(selectedModelSize)
                    showModelSizeDialog = false
                },
                onDismiss = { showModelSizeDialog = false }
            )
        }
    }
    
    // Диалог выбора языка
    AnimatedVisibility(
        visible = showLanguageDialog,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        if (showLanguageDialog) {
            LanguageSelectionDialog(
                availableLanguages = availableLanguages,
                selectedLanguage = selectedLanguage,
                onLanguageSelected = { selectedLanguage = it },
                onConfirm = {
                    onLanguageSelected(selectedLanguage)
                    showLanguageDialog = false
                },
                onDismiss = { showLanguageDialog = false }
            )
        }
    }
    
    // Диалог подтверждения сброса
    if (showResetConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmationDialog = false },
            title = { Text("Сброс настроек") },
            text = { Text("Вы уверены, что хотите сбросить все настройки к значениям по умолчанию?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetToDefaults()
                        showResetConfirmationDialog = false
                    }
                ) {
                    Text("Сбросить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmationDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

/**
 * Секция настроек с заголовком.
 */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

/**
 * Элемент настройки с заголовком, подзаголовком и значением.
 */
@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Диалог выбора размера модели.
 */
@Composable
private fun ModelSizeSelectionDialog(
    availableSizes: List<String>,
    selectedSize: String,
    onSizeSelected: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите размер модели") },
        text = {
            Column {
                availableSizes.forEach { size ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSizeSelected(size) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedSize == size,
                            onClick = { onSizeSelected(size) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = size,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = getModelSizeDescription(size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Подтвердить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

/**
 * Диалог выбора языка.
 */
@Composable
private fun LanguageSelectionDialog(
    availableLanguages: List<Pair<String, String>>,
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите язык") },
        text = {
            Column {
                availableLanguages.forEach { (code, displayName) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(code) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedLanguage == code,
                            onClick = { onLanguageSelected(code) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Подтвердить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

/**
 * Информационная карточка с описанием настроек.
 */
@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "О настройках",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "• Большие модели точнее, но требуют больше памяти\n• Автоопределение языка может замедлить распознавание\n• Изменения применяются к новым записям",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

/**
 * Получение описания размера модели.
 */
private fun getModelSizeDescription(size: String): String {
    return when (size) {
        "tiny" -> "~75MB, быстрая, низкое качество"
        "base" -> "~150MB, баланс скорости и качества"
        "small" -> "~500MB, хорошее качество"
        "medium" -> "~1.5GB, высокое качество"
        "large" -> "~3GB, максимальное качество"
        else -> "Неизвестный размер"
    }
}