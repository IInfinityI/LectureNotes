=== БЛОК: Transcription (Распознавание речи) ===

ЧТО ДЕЛАЕТ:
- Загружает модель Whisper.cpp один раз при инициализации
- Принимает аудио-чанки (PCM 16-bit, 16kHz, mono)
- Возвращает распознанный текст

К ЧЕМУ ПОДКЛЮЧЕН:
- native-lib.cpp (JNI-обертка над Whisper.cpp)
- MainActivity.kt (вызывает processChunk())
- RecordingService.kt (отдает аудио-чанки через Flow)

КОНТРАКТЫ:
- initialize(): Boolean — загрузка модели
- shutdown() — освобождение памяти
- processChunk(audioData: ByteArray, language: String): String
- processFile(audioPath: String, language: String): String
- isReady(): Boolean

ЗАВИСИМОСТИ:
- whisper.cpp (C++ библиотека)
- Android Context (для доступа к файлам)

ПРАВИЛА:
- Модель грузится ОДИН раз
- Нельзя вызывать processChunk() до initialize()
- После shutdown() нужно снова вызвать initialize()