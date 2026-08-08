=== БЛОК: UI (Presentation) ===

ЧТО ДЕЛАЕТ:
- Отображает экран стриминговой записи лекции (StreamingScreen)
- Показывает live-транскрибацию в реальном времени
- Показывает список сохранённых записей (RecordingsListScreen)
- Открывает детальную запись (RecordingDetailScreen)
- Управляет настройками (SettingsScreen)
- Управляет состоянием через RecordingViewModel

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

ОБРАБОТКА ТЕКСТА:
- Во время live-записи применяется только TextProcessor.applyVoiceCommands()
- После остановки применяется полный TextProcessor.process()
- Это предотвращает повторное вычисление арифметики и дублирование результатов

ЗАВИСИМОСТИ:
- AndroidX Lifecycle
- Jetpack Compose (Material3)
- Kotlin Coroutines / Flow
- Room через data-блок
- textprocessor-блок

ПРАВИЛА:
- UI не содержит бизнес-логику
- Все операции с БД и транскрибацией только через ViewModel
- Модель Whisper не управляется напрямую из Composable
- Состояние экрана только через StateFlow

ИСТОРИЯ ИЗМЕНЕНИЙ:
08.08.2026 - RecordingViewModel расширен:
  * Добавлены allRecordings, getRecordingById, deleteRecording(id), updateRecordingTitle
  * Подключён TextProcessor (voice commands в live, полный pipeline при финализации)
  * Добавлен расчёт durationSeconds
  * Исправлен regex для wordCount: "s+" → "\\s+"