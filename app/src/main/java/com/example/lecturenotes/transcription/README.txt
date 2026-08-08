КОНТРАКТЫ:
- initialize(): Boolean — загрузка модели (вызывается один раз)
- shutdown() — освобождение памяти
- processChunk(audioData: ByteArray, language: String): String — синхронная транскрибация чанка
- processFile(audioPath: String, language: String): String — транскрибация полного файла
- isReady(): Boolean

ОПТИМИЗАЦИИ (whisper_jni_bridge.cpp):
- Модель загружается один раз (проверка по пути файла)
- Параметры стриминга создаются при initModel, не на каждый чанк
- n_threads = 4 для ARM64
- Резервирование памяти для результата

ИСТОРИЯ ИЗМЕНЕНИЙ:
08.08.2026 - Оптимизация производительности стриминга:
  * Переход на переиспользуемые streaming_wparams
  * Увеличение n_threads до 4
  * Добавлен result.reserve() для уменьшения аллокаций