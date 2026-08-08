=== БЛОК: Transcription (Обёртка над JNI) ===

ЧТО ДЕЛАЕТ:
- Управляет жизненным циклом моделей Whisper (загрузка, переключение, освобождение)
- Копирует модели из assets во внутреннее хранилище
- Предоставляет высокоуровневый API для транскрибации (processChunk, processFile)
- Thread-safe: все операции защищены Mutex

ДВУХПОТОЧНАЯ АРХИТЕКТУРА (Приоритет 3):
- STREAMING_MODEL = "ggml-tiny.bin" — быстрая модель для live-транскрибации
- FINAL_MODEL = "ggml-base-q5_0.bin" — точная модель для финальной транскрибации
- Модели загружаются независимо в пуле (native-слой)
- processChunk() использует streaming-модель
- processFile() использует финальную модель (ленивая загрузка)

К ЧЕМУ ПОДКЛЮЧЕН:
- WhisperTranscriber → JNI (whisper_jni_bridge.cpp)
- JNI → Whisper.cpp (C++ библиотека)
- WhisperTranscriber → Android Context (для доступа к assets и filesDir)

КОНТРАКТ WhisperTranscriber:
- initialize(modelName): Boolean — загрузка streaming-модели
- processChunk(audioData, language): String — транскрибация чанка (streaming-модель)
- processFile(audioPath, language): String — транскрибация файла (финальная модель)
- shutdown() — освобождение всех моделей
- isReady(): Boolean — проверка готовности streaming-модели
- close() — алиас для shutdown()

JNI API (новый, с явным выбором модели):
- nativeInitModel(modelPath): Boolean
- nativeIsModelLoaded(modelPath): Boolean
- nativeTranscribeChunkWithModel(audioData, language, modelPath): String
- nativeTranscribeAudioWithModel(audioPath, language, modelPath): String
- nativeReleaseModelByPath(modelPath)
- nativeReleaseAllModels()

JNI API (legacy, для обратной совместимости):
- initModel(modelPath) / releaseModel() / transcribeChunk() / transcribeAudio()
- Работают через g_default_model_path в native-слое

ЗАВИСИМОСТИ:
- Kotlin Coroutines (Dispatchers.IO, Mutex)
- Android Context (assets, filesDir)
- JNI (System.loadLibrary)

ПРАВИЛА:
- UI и ViewModel не вызывают JNI напрямую — только через WhisperTranscriber
- Все операции с моделями асинхронные (suspend functions)
- Модели копируются из assets один раз при первой загрузке
- Проверка размера файла (>10MB) для защиты от Git LFS pointers

ОГРАНИЧЕНИЯ:
- Память: две модели одновременно ≈ 135MB (tiny 75MB + base-q5_0 60MB)
- Если ggml-tiny.bin отсутствует в assets — initialize() вернёт false
- Если ggml-base-q5_0.bin отсутствует — processFile() вернёт пустую строку

ИСТОРИЯ ИЗМЕНЕНИЙ:
08.08.2026 - Приоритет 3: двухпоточная архитектура
  * Добавлены внешние объявления для нового JNI API
  * initialize() загружает STREAMING_MODEL (tiny) вместо base
  * Добавлен ensureFinalModelLoaded() для ленивой загрузки финальной модели
  * processChunk() использует streaming-модель
  * processFile() использует финальную модель
  * shutdown() вызывает nativeReleaseAllModels()
  * Legacy JNI API сохранён для обратной совместимости