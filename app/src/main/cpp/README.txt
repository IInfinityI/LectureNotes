=== БЛОК: Native (C++ JNI, Whisper.cpp) ===

ЧТО ДЕЛАЕТ:
- Хранит пул моделей Whisper (каждая — отдельный whisper_context)
- Конвертирует PCM16 в float32
- Запускает whisper_full для чанков (single_segment) и полных файлов

АРХИТЕКТУРА ПУЛА:
- g_models: map<model_path, shared_ptr<ModelHandle>>
- ModelHandle содержит ctx + персональный mutex
- Стриминг (tiny) и финальная транскрибация (base) работают параллельно
- shared_ptr защищает от use-after-free при удалении модели
- release ждёт завершения активной транскрибации перед whisper_free

КОНТРАКТ (новый API, JNI):
- nativeInitModel(modelPath): Boolean — загрузить модель в пул (идемпотентно)
- nativeIsModelLoaded(modelPath): Boolean
- nativeTranscribeChunkWithModel(audioData, language, modelPath): String
- nativeTranscribeAudioWithModel(audioPath, language, modelPath): String
- nativeReleaseModelByPath(modelPath)
- nativeReleaseAllModels()

КОНТРАКТ (legacy API, сохранён для обратной совместимости):
- initModel(modelPath) — загрузка + установка как default
- releaseModel() — освобождение default-модели
- transcribeChunk(audioData, language) — через default-модель
- transcribeAudio(audioPath, language) — через default-модель

ПАРАМЕТРЫ WHISPER:
- WHISPER_SAMPLING_GREEDY
- n_threads = 4
- no_context = false (контекст между чанками)
- Чанки: single_segment = true, no_timestamps = true
- Файлы: single_segment = false

ОГРАНИЧЕНИЯ:
- Память: каждая модель в RAM (~75MB tiny, ~60MB base-q5_0).
  Две модели одновременно ≈ 135MB — приемлемо для 4GB+ устройств.
- Политика загрузки (какие модели держать в памяти) — в Kotlin-блоке
  transcription, не здесь.

ИСТОРИЯ ИЗМЕНЕНИЙ:
08.08.2026 - Приоритет 3: пул моделей
  * Добавлен новый API с явным выбором модели по пути
  * Персональные mutex на модель вместо одного глобального
  * n_threads: 2 → 4
  * Legacy API сохранён, работает через g_default_model_path