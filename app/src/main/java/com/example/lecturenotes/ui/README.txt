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
- TextProcessor (постобработка текста: голосовые команды в live, полный pipeline при финализации)
- Room DAO (история записей)

КОНТРАКТЫ RecordingViewModel:
- uiState: StateFlow<TranscriptionState> — состояние экрана записи
- recordings: StateFlow<List<Recording>> — список записей
- allRecordings: StateFlow<List<Recording>> — алиас для RecordingsListScreen
- getRecordingById(id): Flow<Recording?> — запись для RecordingDetailScreen
- startRecording() / stopRecording() / saveRecording()
- deleteRecording(recording) — удаление по объекту
- deleteRecording(id) — удаление по ID
- updateRecordingTitle(id, newTitle) — переименование записи
- errorMessage: StateFlow<String?> — ошибки для UI

КОНТРАКТЫ ЭКРАНОВ:
- StreamingScreen(uiState, onStartClick, onStopClick, onSaveClick, onSettingsClick, onHistoryClick, onBackClick)
- RecordingsListScreen(viewModel, onNavigateToRecording, onNavigateToSettings, onBack)
- RecordingDetailScreen(recordingId, viewModel, onBack, onDelete)
- SettingsScreen(currentModelSize, currentLanguage, ...)

НАВИГАЦИЯ:
- streaming → settings (onSettingsClick)
- streaming → history (onHistoryClick)
- history → detail/{recordingId} (onNavigateToRecording)
- detail → history (onBack или onDelete с автоматическим popBackStack)
- settings → streaming (onBackClick)
- history → streaming (onBack)

ОБРАБОТКА ТЕКСТА:
- Во время live-записи применяется только TextProcessor.applyVoiceCommands()
- После остановки применяется полный TextProcessor.process()
- Это предотвращает повторное вычисление арифметики и дублирование результатов

ЛОКАЛИЗАЦИЯ:
- StreamingScreen полностью локализован на русский язык

ЗАВИСИМОСТИ:
- AndroidX Lifecycle
- Jetpack Compose (Material3)
- AndroidX Navigation Compose
- Kotlin Coroutines / Flow
- lifecycle-runtime-compose (collectAsStateWithLifecycle)
- Room через data-блок
- textprocessor-блок

ПРАВИЛА:
- UI не содержит бизнес-логику
- Все операции с БД и транскрибацией только через ViewModel
- Модель Whisper не управляется напрямую из Composable
- Состояние экрана только через StateFlow

ИСТОРИЯ ИЗМЕНЕНИЙ:
08.08.2026 - StreamingScreen: добавлена кнопка истории
  * Добавлен параметр onHistoryClick в сигнатуру
  * Добавлена IconButton с Icons.Filled.List в TopAppBar
  * Локализация всех текстов на русский
08.08.2026 - MainActivity: подключены экраны истории
  * Роут "history" теперь содержит RecordingsListScreen
  * Добавлен роут "detail/{recordingId}" для RecordingDetailScreen
  * При удалении записи на детальном экране — автоматический popBackStack
08.08.2026 - RecordingViewModel расширен:
  * Добавлены allRecordings, getRecordingById, deleteRecording(id), updateRecordingTitle
  * Подключён TextProcessor (voice commands в live, полный pipeline при финализации)
  * Добавлен расчёт durationSeconds
  * Исправлен regex для wordCount: "s+" → "\\s+"