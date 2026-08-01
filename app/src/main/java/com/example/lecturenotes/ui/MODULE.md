```markdown
# UI Module

## Описание

Модуль пользовательского интерфейса приложения для записи и транскрибирования лекций. Реализован на Jetpack Compose с использованием MVVM-архитектуры.

## Файлы модуля

| Файл | Назначение |
|------|-----------|
| StreamingScreen.kt | Экран потоковой записи с отображением распознанного текста в реальном времени |
| SettingsScreen.kt | Экран настроек (размер модели Whisper, язык распознавания) |
| RecordingViewModel.kt | ViewModel для управления записями (CRUD операции с Room БД) |
| SettingsViewModel.kt | ViewModel для управления настройками (DataStore) |
| MODULE.md | Этот файл — документация модуля |

## Архитектура

### StreamingScreen
- **Вход:** StreamingUiState (sealed class с 5 состояниями: Idle/Listening/Recording/Finalizing/Error)
- **Выход:** Callback-и для управления записью (onStartClick, onStopClick, onSaveClick)
- **Зависимости:** Не зависит напрямую от ViewModel — получает состояние через параметры

### SettingsScreen
- **Вход:** Текущие настройки, списки доступных значений
- **Выход:** Callback-и для изменения настроек (onModelSizeSelected, onLanguageSelected)
- **Зависимости:** Не зависит напрямую от ViewModel — получает данные через параметры

### RecordingViewModel
- **Отвечает за:**
  - Получение списка записей из Room БД
  - Добавление новых записей (с audioPath и durationSeconds)
  - Обновление и удаление записей
  - Управление состоянием UI через RecordingsUiState
  - Управление состоянием операций через SaveOperationState
- **Зависимости:** AppDatabase (Room), RecordingDao

### SettingsViewModel
- **Отвечает за:**
  - Хранение настроек в DataStore (размер модели, язык)
  - Валидацию входных данных
  - Уведомление других модулей об изменении настроек через settingsChanged: Flow<Long>
  - Сброс настроек к дефолтным
- **Зависимости:** DataStore<Preferences>, SettingsConstants

## Куда подключён

### Зависит от:
- **Data модуль** — AppDatabase, Recording, RecordingDao (для хранения записей)
- **AndroidX** — Compose, Lifecycle, DataStore, Room

### Подключается к:
- **Presentation модуль** (MainActivity.kt) — использует все 4 компонента модуля
- **Transcription модуль** — косвенно через SettingsViewModel (настройки Whisper)

## История изменений

### Версия 2.0 (текущая)

#### RecordingViewModel.kt
- ДОБАВЛЕНО: RecordingsUiState — sealed class для управления состоянием UI (Loading/Success/Error)
- ДОБАВЛЕНО: SaveOperationState — sealed class для состояния операции сохранения
- ДОБАВЛЕНО: uiState: StateFlow<RecordingsUiState> — основное состояние для UI
- ДОБАВЛЕНО: saveOperationState: StateFlow<SaveOperationState> — состояние текущей операции
- ИЗМЕНЕНО: allRecordings теперь использует SharingStarted.Eagerly вместо Lazily
- ИЗМЕНЕНО: addRecording() теперь принимает durationSeconds и audioPath
- ДОБАВЛЕНО: Обработка ошибок через try/catch во всех методах
- ДОБАВЛЕНО: clearError() для сброса состояния ошибки
- ДОБАВЛЕНО: getRecordingById() для получения записи по ID

#### SettingsViewModel.kt
- ДОБАВЛЕНО: SettingsConstants — объект с константами (размеры моделей, языки, ключи DataStore)
- ДОБАВЛЕНО: Валидация входных данных в setModelSize() и setLanguage()
- ДОБАВЛЕНО: SettingsSaveState — sealed class для состояния операции сохранения
- ДОБАВЛЕНО: settingsChanged: Flow<Long> — уведомление об изменении настроек для других модулей
- ДОБАВЛЕНО: resetToDefaults() — сброс настроек к дефолтным
- ДОБАВЛЕНО: getLanguageDisplayName() — получение человекочитаемого названия языка
- ИЗМЕНЕНО: Убрана жёсткая зависимость от захардкоженных значений

#### StreamingScreen.kt
- ДОБАВЛЕНО: StreamingUiState — sealed class с 5 состояниями (Idle/Listening/Recording/Finalizing/Error)
- ИЗМЕНЕНО: Сигнатура StreamingScreen — теперь принимает uiState: StreamingUiState вместо liveText: String
- ДОБАВЛЕНО: Кнопки управления записью (Начать/Остановить/Сохранить)
- ДОБАВЛЕНО: Индикатор прогресса финализации
- ДОБАВЛЕНО: Анимированный индикатор прослушивания
- ИЗМЕНЕНО: Исправлена опечатка "апись" → "Запись лекции"
- ДОБАВЛЕНО: Обработка всех состояний UI (пусто, ошибка, загрузка)
- ДОБАВЛЕНО: Форматирование длительности в "MM:SS"
- ДОБАВЛЕНО: Нижняя панель с кнопками управления

#### SettingsScreen.kt
- ДОБАВЛЕНО: Сохранение состояния диалогов через remember { mutableStateOf() }
- ДОБАВЛЕНО: Анимации открытия/закрытия диалогов через AnimatedVisibility
- ДОБАВЛЕНО: Визуальная индикация текущего выбора (RadioButton + выделение)
- ДОБАВЛЕНО: Информационная карточка с описанием настроек
- ДОБАВЛЕНО: Диалог подтверждения сброса настроек
- ИЗМЕНЕНО: Структура экрана — разделена на секции (Модель, Язык, Сброс)
- ДОБАВЛЕНО: getModelSizeDescription() — человекочитаемые описания размеров моделей
- ИЗМЕНЕНО: Контракт экрана — теперь принимает все данные через параметры

#### Recording.kt (Data модуль, но изменён для совместимости)
- ДОБАВЛЕНО: поле audioPath: String? = null — путь к аудиофайлу
- ДОБАВЛЕНО: поле createdAt: Long = System.currentTimeMillis() — timestamp создания

## Контракты и API

### StreamingScreen
```kotlin
@Composable
fun StreamingScreen(
    uiState: StreamingUiState,
    onSaveClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
    onStartRecordingClick: () -> Unit = {},
    onStopRecordingClick: () -> Unit = {}
)
```

### SettingsScreen
```kotlin
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
)
```

### RecordingViewModel
```kotlin
class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    val uiState: StateFlow<RecordingsUiState>
    val saveOperationState: StateFlow<SaveOperationState>
    val allRecordings: StateFlow<List<Recording>>
    
    fun addRecording(transcription: String, durationSeconds: Int = 0, audioPath: String? = null)
    fun updateRecording(recording: Recording)
    fun deleteRecording(recording: Recording)
    fun clearError()
    fun getRecordingById(id: Long): Recording?
}
```

### SettingsViewModel
```kotlin
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    val modelSize: StateFlow<String>
    val language: StateFlow<String>
    val saveState: StateFlow<SettingsSaveState>
    val settingsChanged: Flow<Long>
    
    fun setModelSize(size: String)
    fun setLanguage(language: String)
    fun resetToDefaults()
    fun clearError()
    fun getLanguageDisplayName(code: String): String
}
```

## Следующие шаги

Для полной интеграции модуля необходимо:
1. Переписать MainActivity.kt (Presentation модуль) для использования Compose
2. Подключить все 4 компонента UI модуля в MainActivity
3. Интегрировать с RecordingService (подписка на audioChunks)
4. Интегрировать с TextProcessor (обработка текста)
5. Интегрировать с WhisperTranscriber (распознавание речи)
```



