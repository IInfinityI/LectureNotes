=== БЛОК: Data (Room DB, Repository) ===

ЧТО ДЕЛАЕТ:
- Хранит историю записей через Room DB
- Публикует аудио-чанки между RecordingService и ViewModel через SharedFlow
- Управляет ошибками от сервиса через StateFlow

КОНТРАКТ AudioChunkRepository:
- audioChunks: SharedFlow<ByteArray> — поток аудио-чанков (replay = 0, extraBufferCapacity = 64)
- errors: StateFlow<String?> — ошибки от RecordingService
- emitChunk(chunk: ByteArray) — публикация чанка (suspend)
- emitError(message: String) — публикация ошибки
- clearError() — очистка ошибки

ОБРАБОТКА ОШИБОК:
- RecordingService вызывает emitError() при ошибках микрофона, permission, storage
- ViewModel подписывается на errors и обновляет uiState.error
- UI отображает ошибку из uiState.error
- Пользователь вызывает clearError() через ViewModel для очистки

КОНТРАКТ Room DAO:
- getAllRecordings(): Flow<List<Recording>>
- insert(recording)
- delete(recording)
- deleteById(id)
- update(recording)

ЗАВИСИМОСТИ:
- Room Database
- Kotlin Coroutines / Flow
- SharedFlow для аудио-чанков
- StateFlow для ошибок

ПРАВИЛА:
- AudioChunkRepository — singleton (object)
- SharedFlow с replay = 0 (новые подписчики не получают старые чанки)
- extraBufferCapacity = 64 для защиты от overflow при медленной транскрибации
- StateFlow для ошибок (новые подписчики получают последнюю ошибку)

ИСТОРИЯ ИЗМЕНЕНИЙ:
08.08.2026 - Приоритет 4: обработка ошибок
  * Добавлен StateFlow<String?> для ошибок от RecordingService
  * Добавлен emitError(message) для публикации ошибок
  * Добавлен clearError() для очистки ошибок
  * RecordingService теперь публикует ошибки при проблемах с микрофоном/permission/storage