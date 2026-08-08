=== БЛОК: UI (Presentation) ===

ЧТО ДЕЛАЕТ:
- Отображает экран стриминговой записи лекции (StreamingScreen)
- Показывает live-транскрибацию в реальном времени
- Показывает список сохранённых записей (RecordingsListScreen)
- Открывает детальную запись (RecordingDetailScreen)
- Управляет настройками (SettingsScreen)
- Управляет состоянием через RecordingViewModel и SettingsViewModel

ДВУХПОТОЧНАЯ АРХИТЕКТУРА (Приоритет 3):
- Streaming-модель (tiny): быстрая live-транскрибация во время записи
- Final-модель (base-q5_0): точная транскрибация при сохранении
- Модели загружаются независимо в пуле (native-слой)
- processChunk() использует streaming-модель
- processFile() использует финальную модель (при сохранении)

К ЧЕМУ ПОДКЛЮЧЕН:
- WhisperTranscriber (инициализация моделей, транскрибация чанков и файлов)
- RecordingService (аудио-чанки через Flow из AudioChunkRepository)
- TextProcessor (постобработка текста: голосовые команды в live, полный pipeline при финализации)
- Room DAO (история записей)
- SettingsViewModel (настройки языка и модели)

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
- При сохранении выполняется финальная транскрибация через processFile()
- Результат финальной транскрибации проходит полный TextProcessor.process()
- Fallback: если финальная транскрибация не удалась — используется live-текст

ИНТЕГРАЦИЯ С НАСТРОЙКАМИ:
- RecordingViewModel получает SettingsViewModel через конструктор
- Язык читается из settingsViewModel.language при старте записи
- Размер модели для стриминга ВСЕГДА tiny (не зависит от настроек)
- Подписка на settingsChanged для реактивного переключения модели

ЗАВИСИМОСТИ:
- AndroidX Lifecycle
- Jetpack Compose (Material3)
- AndroidX Navigation Compose
- Kotlin Coroutines / Flow
- lifecycle-runtime-compose (collectAsStateWithLifecycle)
- Room через data-блок
- textprocessor-блок
- transcription-блок
- SettingsViewModel

ПРАВИЛА:
- UI не содержит бизнес-логику
- Все операции с БД и транскрибацией только через ViewModel
- Модель Whisper не управляется напрямую из Composable
- Состояние экрана только через StateFlow

ИСТОРИЯ ИЗМЕНЕНИЙ:
08.08.2026 - Приоритет 3: интеграция двухпоточной архитектуры
  * RecordingViewModel получает SettingsViewModel через конструктор
  * startRecording() читает язык из настроек
  * saveRecording() выполняет финальную транскрибацию через processFile()
  * Добавлен fallback на live-текст если финальная транскрибация не удалась
  * Защита от race condition: нельзя сохранить во время записи
  * Подписка на settingsChanged для реактивного переключения модели
08.08.2026 - Приоритет 4: индикатор загрузки модели + багфикс
  * TranscriptionState: добавлено поле isLoadingModel
  * RecordingViewModel: выставляет isLoadingModel при init и при смене настроек
  * startRecording() заблокирован пока модель грузится или не готова
  * ИСПРАВЛЕНО: updateRecordingTitle() не работал из-за stateIn(...).value → заменено на firstOrNull()