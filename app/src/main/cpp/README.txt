=== БЛОК: Native (C++ JNI) ===

ЧТО ДЕЛАЕТ:
- Обертка над Whisper.cpp для Android
- Загружает модель в память (один раз при инициализации)
- Выполняет транскрибацию аудио (чанками или целиком)

К ЧЕМУ ПОДКЛЮЧЕН:
- WhisperTranscriber.kt (вызывает нативные методы через JNI)

НАТИВНЫЕ МЕТОДЫ (JNI):
- Java_com_example_lecturenotes_transcription_WhisperTranscriber_initModel()
- Java_com_example_lecturenotes_transcription_WhisperTranscriber_releaseModel()
- Java_com_example_lecturenotes_transcription_WhisperTranscriber_transcribeChunk()
- Java_com_example_lecturenotes_transcription_WhisperTranscriber_transcribeAudio()

ЗАВИСИМОСТИ:
- whisper.cpp (C++ библиотека, лежит в папке whisper.cpp/)
- Android NDK (JNI, логирование)

ПРАВИЛА:
- Модель хранится в глобальной переменной g_ctx (статический контекст)
- Нельзя вызывать транскрибацию до успешного вызова initModel()
- Освобождение памяти (releaseModel) обязательно при закрытии приложения
- Формат аудио на входе: PCM 16-bit, 16kHz, Mono