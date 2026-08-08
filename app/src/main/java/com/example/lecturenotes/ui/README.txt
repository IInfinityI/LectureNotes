=== БЛОК: UI (Presentation) ===

ЧТО ДЕЛАЕТ:
- Отображает экран стриминговой записи лекции
- Показывает live-транскрибацию
- Показывает список сохранённых записей
- Открывает детальную запись
- Позволяет редактировать название, удалять и просматривать записи
- Управляет состоянием через RecordingViewModel

К ЧЕМУ ПОДКЛЮЧЕН:
- WhisperTranscriber (инициализация модели, транскрибация чанков)
- RecordingService (получение аудио-чанков через Flow)
- TextProcessor (постобработка распознанного текста)
- Room DAO (история записей)

КОНТРАКТЫ RecordingViewModel:
- uiState: StateFlow<TranscriptionState> — состояние экрана записи
- recordings: StateFlow<List<Recording>> — список записей
- allRecordings: StateFlow<List<Recording>> — алиас для RecordingsListScreen
- getRecordingById(id): Flow<Recording?> — запись для RecordingDetailScreen
- startRecording() — запуск записи
- stopRecording() — остановка записи и финальная постобработка текста
- saveRecording() — сохранение в БД
- deleteRecording(recording) — удаление по объекту
- deleteRecording(id) — удаление по ID
- updateRecordingTitle(id, newTitle) — переименование записи
- errorMessage: StateFlow<String?> — ошибки для UI

ОБРАБОТКА ТЕКСТА:
- Во время live-записи применяется только applyVoiceCommands()
- После остановки применяется полный TextProcessor.process()
- Это предотвращает повторное вычисление арифметики и дублирование результатов

ЗАВИСИМОСТИ:
- AndroidX Lifecycle
- Jetpack Compose
- Kotlin Coroutines
- Flow
- Room через data-блок
- textprocessor-блок

ПРАВИЛА:
- UI не содержит бизнес-логику
- Все операции с БД и транскрибацией только через ViewModel
- Модель Whisper не управляется напрямую из Composable
- Состояние экрана только через StateFlow

ИСТОРИЯ ИЗМЕНЕНИЙ:
08.08.2026 - Расширен контракт RecordingViewModel:
  * Добавлен allRecordings для RecordingsListScreen
  * Добавлен getRecordingById(id) для RecordingDetailScreen
  * Добавлен deleteRecording(id)
  * Добавлен updateRecordingTitle(id, newTitle)
  * Подключён TextProcessor
  * Добавлен расчёт durationSeconds