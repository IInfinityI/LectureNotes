=== БЛОК: UI (Presentation) ===

ЧТО ДЕЛАЕТ:
- Отображает экран стриминговой записи лекции (StreamingScreen)
- Показывает live-транскрибацию в реальном времени
- Показывает список сохранённых записей (RecordingsListScreen)
- Открывает детальную запись (RecordingDetailScreen)
- Управляет настройками (SettingsScreen)
- Управляет состоянием через RecordingViewModel и SettingsViewModel

К ЧЕМУ ПОДКЛЮЧЕН:
- WhisperTranscriber (инициализация модели, транскрибация чанков)
- RecordingService (аудио-чанки через Flow из AudioChunkRepository)
- TextProcessor (постобработка текста при финализации)
- Room DAO (история записей)

КОНТРАКТЫ RecordingViewModel:
- uiState: StateFlow<TranscriptionState> — состояние экрана записи
- recordings / allRecordings: StateFlow<List<Recording>> — список записей
- getRecordingById(id): Flow<Recording?> — запись для детального экрана
- startRecording() / stopRecording() / saveRecording()
- deleteRecording(recording) / deleteRecording(id)
- updateRecordingTitle(id, newTitle)
- errorMessage: StateFlow<String?>

КОНТРАКТЫ ЭКРАНОВ:
- RecordingsListScreen(viewModel, onNavigateToRecording, onNavigateToSettings, onBack)
- StreamingScreen(uiState, onStartClick, onStopClick, onSaveClick, onSettingsClick, onBackClick)
- SettingsScreen(currentModelSize, currentLanguage, ...)

ЗАВИСИМОСТИ:
- Jetpack Compose (Material3)
- AndroidX Navigation Compose
- Kotlin Coroutines / Flow
- lifecycle-runtime-compose (collectAsStateWithLifecycle)

ПРАВИЛА:
- UI не содержит бизнес-логики
- Все операции с БД и транскрибацией только через ViewModel
- Состояние экрана только через StateFlow
- Никаких глобальных синглтонов для передачи данных

ИСТОРИЯ ИЗМЕНЕНИЙ:
08.08.2026 - RecordingsListScreen переписан на Material3:
  * Удалены несуществующие Material2 API (Modifier.swipeToDismiss, rememberDismissState)
  * Добавлен SwipeToDismissBox + rememberSwipeToDismissBoxState
  * Добавлено пустое состояние списка
  * Исправлены отсутствующие импорты (background, Icons.Delete)
08.08.2026 - RecordingViewModel расширен:
  * Добавлены allRecordings, getRecordingById, deleteRecording(id), updateRecordingTitle
  * Подключён TextProcessor, добавлен расчёт durationSeconds