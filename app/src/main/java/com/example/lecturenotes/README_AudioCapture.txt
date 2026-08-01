=== БЛОК: AudioCapture (RecordingService.kt) ===

ЧТО ДЕЛАЕТ:
- Фоновая запись аудио через AudioRecord (16kHz, MONO, PCM_16BIT)
- Нарезка аудио на 2-секундные чанки (64000 байт)
- Передача чанков через SharedFlow подписчикам (MainActivity)
- Поддержка паузы/возобновления записи
- Foreground Service с уведомлением

К ЧЕМУ ПОДКЛЮЧЕН:
- MainActivity.kt (подписан на audioChunks, вызывает start/stop/pause/resume)
- WhisperTranscriber (получает 2-секундные чанки для транскрибации)

КОНТРАКТЫ:
- audioChunks: SharedFlow<ByteArray> — поток 2-секундных чанков (64000 байт)
- ACTION_START — начать запись
- ACTION_STOP — остановить запись и освободить ресурсы
- ACTION_PAUSE — приостановить запись (AudioRecord работает, но данные не читаются)
- ACTION_RESUME — возобновить запись

ЗАВИСИМОСТИ:
- Android AudioRecord API
- Kotlin Coroutines + SharedFlow
- Android Foreground Service

ПРАВИЛА:
- Чанки строго 2 секунды (буферизация через ByteArrayOutputStream)
- При паузе AudioRecord не освобождается, просто не читаются данные
- Все ресурсы освобождаются в onDestroy()
- Логирование всех ключевых событий через Log.i/w/e

ИСТОРИЯ ИЗМЕНЕНИЙ:

[01.08.2026] — ЗАВЕРШЕНО
ИЗМЕНЕНО:
1. Добавлена буферизация до 2-секундных чанков (64000 байт)
   - Раньше: чанки отправлялись сразу (~100-500 мс)
   - Теперь: используется ByteArrayOutputStream для накопления
   
2. Добавлена поддержка паузы/возобновления
   - Добавлены ACTION_PAUSE и ACTION_RESUME
   - При паузе: Thread.sleep(100), данные не читаются
   
3. Добавлено логирование всех событий
   - Log.i() для информации (старт/стоп)
   - Log.w() для предупреждений (попытка паузы без записи)
   - Log.e() для ошибок (отказ в разрешениях)
   
4. Добавлена обработка ошибок
   - Try-catch для SecurityException
   - Проверка STATE_INITIALIZED для AudioRecord
   - Освобождение ресурсов в onDestroy()
   
5. Добавлен вспомогательный класс ByteArrayOutputStream
   - Методы: write(), size(), toByteArray(), reset()
   
6. Добавлены константы
   - SAMPLE_RATE = 16000
   - CHUNK_DURATION_SECONDS = 2
   - CHUNK_SIZE_BYTES = 64000

ЗАТРОНУТЫЕ БЛОКИ:
- MainActivity: получает 2-секундные чанки вместо мелких
- WhisperTranscriber: размер чанка теперь фиксирован (64000 байт)

НЕ ЗАТРОНУТЫ:
- Data (Room DB)
- UI (ViewModel)