=== БЛОК: UI (Presentation) ===

ЧТО ДЕЛАЕТ:
- Отображает экран записи лекции
- Управляет состоянием UI (блокировка/разблокировка кнопок)
- Показывает распознанный текст в реальном времени
- Сохраняет записи в БД через ViewModel

К ЧЕМУ ПОДКЛЮЧЕН:
- WhisperTranscriber (инициализация модели, транскрибация)
- RecordingService (получение аудио-чанков через Flow)
- RecordingViewModel (сохранение записей в БД)

КОНТРАКТЫ:
- btnStart.setOnClickListener → startRecordingViaService()
- btnStop.setOnClickListener → stopRecordingViaService()
- tvResult.text → отображение распознанного текста

ЗАВИСИМОСТИ:
- AndroidX AppCompat
- Kotlin Coroutines (lifecycleScope)
- Android ViewBinding

ПРАВИЛА:
- UI не содержит бизнес-логики (никаких прямых запросов к БД)
- Все тяжелые операции (транскрибация) в корутинах
- lifecycleScope автоматически отменяет корутины при уничтожении Activity
- Модель Whisper инициализируется в onCreate, освобождается в onDestroy