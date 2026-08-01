# Модуль: Transcription

## Что делает

Обёртка над Whisper.cpp для оффлайн-транскрипции аудио в реальном времени.
Принимает PCM-чанки (16-bit, 16kHz, mono) или полный аудиофайл — возвращает распознанный текст.

## Подключения

| Кто использует | Как |
|---|---|
| `RecordingService` | Подписывается на `audioChunks` (SharedFlow), передаёт чанки в `processChunk()` |
| `MainActivity` | Прямой вызов `transcribeAudio()` для полного файла (legacy, будет удалён) |
| `StreamingScreen` / `RecordingViewModel` | Отображает результат `processChunk()` в UI |
| Настройки (SettingsScreen) | Передают `modelName` и `language` в `initialize()` |

## Файлы модуля

| Файл | Роль |
|---|---|
| `WhisperTranscriber.kt` | Kotlin-обёртка: lifecycle модели, suspend API, копирование из assets |
| `whisper_jni_bridge.cpp` | JNI-мост: конвертация PCM→float, вызов whisper_full, сборка результата |
| `CMakeLists.txt` | Сборка native-библиотеки, линковка whisper.cpp + ggml |

## JNI-контракт
Java_com_example_lecturenotes_transcription_WhisperTranscriber_initModel(String) → Boolean
Java_com_example_lecturenotes_transcription_WhisperTranscriber_releaseModel() → void
Java_com_example_lecturenotes_transcription_WhisperTranscriber_transcribeChunk(ByteArray, String) → String
Java_com_example_lecturenotes_transcription_WhisperTranscriber_transcribeAudio(String, String) → String

Имя native-библиотеки: `libwhisper_jni_bridge.so`
Загрузка: `System.loadLibrary("whisper_jni_bridge")`

## Изменения (2026-08-01)

### whisper_jni_bridge.cpp

| Изменение | Причина |
|---|---|
| Добавлен `std::mutex` + `lock_guard` во всех 4 функциях | Race condition: `releaseModel()` из main thread vs `transcribeChunk()` из IO thread = use-after-free |
| Null-проверки для всех JNI-параметров | Без них segfault при передаче null из Kotlin |
| Проверка `bytes == nullptr` после `GetByteArrayElements` | JNI возвращает null при OOM |
| Проверка `text != nullptr` в цикле сегментов | `whisper_full_get_segment_text` может вернуть null |
| `static_cast<int>(pcmf32.size())` | Убирает warning size_t → int |
| Проверка размера файла в `transcribeAudio` | Пустой файл раньше проходил без ошибки |
| Лог коротких чанков: LOGE → LOGI | Короткий чанк — норма, не ошибка |

### WhisperTranscriber.kt

| Изменение | Причина |
|---|---|
| `AutoCloseable` + `close()` | Гарантия освобождения через `.use { }` |
| `@Volatile` на `isModelInitialized` | Видимость флага между потоками |
| `Mutex` + `withLock` в `initialize()` | Защита от конкурентного initialize/shutdown |
| `suspend` + `Dispatchers.IO` на всех публичных методах | JNI-вызовы блокирующие, нельзя на main thread |
| Параметр `modelName` в `initialize()` | Смена модели из настроек без пересоздания объекта |
| `currentModelName` + логика переключения | Старая модель освобождается перед загрузкой новой |
| `file.exists()` + `file.length()` в `processFile()` | Несуществующий путь раньше уходил в JNI → segfault |
| `internalFile.length() > 0` в `getModelPath()` | Битый файл нулевого размера не считается валидным |
| Удаление битого файла при ошибке копирования | Предотвращает повторное использование повреждённой модели |

### CMakeLists.txt

| Изменение | Причина |
|---|---|
| `CMAKE_CXX_STANDARD 17` | whisper.cpp требует C++17 |
| `-O3 -DNDEBUG` для Release | Без явных флагов CMake может собрать с -O0 → 3-5x медленнее |
| NEON только для `armeabi-v7a` | `-mfpu=neon` невалиден для arm64-v8a (там NEON по умолчанию) |
| `GGML_USE_NEON` define для обоих ABI | Включает SIMD-пути в ggml |
| `BUILD_SHARED_LIBS OFF` | whisper.cpp собирается как статика, не конфликтует с нашей .so |
| `WHISPER_BUILD_EXAMPLES/TESTS/SERVER OFF` | Убирает мусорные таргеты из сборки |
| `target_include_directories` для whisper.h и ggml/include | Без них компилятор не находит заголовки |
| Линковка `whisper` + `ggml` | ggml — отдельная статическая библиотека в новых версиях whisper.cpp |
| `-fvisibility=hidden` | Скрывает внутренние символы, уменьшает размер .so |

## Зависимости

- whisper.cpp (git submodule в `app/src/main/cpp/whisper.cpp/`)
- Android NDK (API 24+)
- Kotlin Coroutines

## Известные ограничения

- Модель `ggml-base.bin` должна лежать в `app/src/main/assets/`
- Минимальный чанк: 32000 байт (1 секунда). Короткие чанки молча пропускаются
- `n_threads = 2` для чанков, `4` для полного файла. Не настраивается из UI (пока)
- Нет поддержки VAD (voice activity detection) — тишина транскрибируется как пустота