=== БЛОК: Data (Хранилище) ===

ЧТО ДЕЛАЕТ:
- Хранит записи лекций в локальной БД (Room/SQLite)
- Предоставляет Flow для реактивного чтения списка записей
- CRUD операции: insert, update, delete

К ЧЕМУ ПОДКЛЮЧЕН:
- RecordingViewModel.kt (вызывает методы DAO)
- MainActivity.kt (через ViewModel сохраняет записи)

КОНТРАКТЫ:
- RecordingDao.getAllRecordings(): Flow<List<Recording>>
- RecordingDao.insert(recording: Recording): Long
- RecordingDao.update(recording: Recording)
- RecordingDao.delete(recording: Recording)

СУЩНОСТИ:
- Recording: id, title, transcription, timestamp, durationSeconds

ЗАВИСИМОСТИ:
- Room (AndroidX)
- Kotlin Coroutines + Flow

ПРАВИЛА:
- Все операции с БД асинхронные (suspend функции)
- Flow автоматически обновляется при изменении данных
- AppDatabase — синглтон, создается один раз