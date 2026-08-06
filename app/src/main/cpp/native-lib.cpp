#include <jni.h>
#include <string>
#include <thread>
#include <vector>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <android/log.h>
#include "whisper.h"

#define TAG "WhisperJNI"

static std::mutex g_mutex;
static std::condition_variable g_cv;
static std::queue<std::vector<float>> g_audio_queue;
static bool g_stop_flag = false;

// Глобальный контекст модели
static struct whisper_context* g_ctx = nullptr;

// Глобальные параметры транскрибации
static struct whisper_full_params g_wparams;

// Буфер для промпта (контекст предыдущего текста)
static std::string g_prompt_buffer = "";

// Функция для логирования
void log_message(const char* level, const char* msg) {
    if (strcmp(level, "DEBUG") == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, TAG, "%s", msg);
    } else if (strcmp(level, "INFO") == 0) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s", msg);
    } else if (strcmp(level, "WARN") == 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s", msg);
    } else if (strcmp(level, "ERROR") == 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", msg);
    }
}

// Worker-функция для обработки аудио из очереди
void audio_worker() {
    while (!g_stop_flag) {
        std::unique_lock<std::mutex> lock(g_mutex);
        g_cv.wait(lock, [] { return !g_audio_queue.empty() || g_stop_flag; });

        if (g_stop_flag && g_audio_queue.empty()) {
            break;
        }

        if (!g_audio_queue.empty()) {
            auto audio_chunk = g_audio_queue.front();
            g_audio_queue.pop();
            lock.unlock();

            if (g_ctx != nullptr) {
                // Очищаем результаты предыдущей транскрибации
                whisper_full_reset_timings(g_ctx);

                // Обновляем промпт, если он используется
                if (!g_prompt_buffer.empty()) {
                    g_wparams.prompt_tokens = nullptr; // Сбрасываем, если используем строку
                    g_wparams.n_prompt_tokens = 0;
                }

                // Устанавливаем текущий промпт (контекст)
                g_wparams.initial_prompt = g_prompt_buffer.c_str();

                // Устанавливаем параметры для стриминга: контекст сохраняется, но управляем окном
                g_wparams.no_context = false; // ВАЖНО: теперь модель будет использовать контекст

                // Применяем параметры к модели
                if (whisper_full(g_ctx, g_wparams, audio_chunk.data(), audio_chunk.size()) != 0) {
                    log_message("ERROR", "whisper_full failed");
                    continue;
                }

                int n_segments = whisper_full_n_segments(g_ctx);
                std::string new_text = "";
                for (int i = 0; i < n_segments; ++i) {
                    const char* text = whisper_full_get_segment_text(g_ctx, i);
                    new_text += text;
                }

                // Обновляем промпт для следующего чанка (например, последние N токенов)
                // Это помогает сохранить контекст между чанками
                if (!new_text.empty()) {
                    g_prompt_buffer += new_text; // Простое добавление текста
                    // Можно ограничить размер буфера, если нужно
                    const size_t max_prompt_len = 512; // Пример ограничения
                    if (g_prompt_buffer.length() > max_prompt_len) {
                        g_prompt_buffer = g_prompt_buffer.substr(g_prompt_buffer.length() - max_prompt_len);
                    }
                }

                // Логируем результат
                log_message("INFO", ("New segment: " + new_text).c_str());
            }
        }
    }
}

// JNI: Инициализация модели
extern "C"
JNIEXPORT void JNICALL
Java_com_example_whisperkotlin_WhisperTranscriber_initModel(JNIEnv *env, jobject thiz, jstring modelPath_) {
    const char *modelPath = env->GetStringUTFChars(modelPath_, 0);
    log_message("INFO", ("Loading model: " + std::string(modelPath)).c_str());

    g_ctx = whisper_init_from_file(modelPath);
    if (g_ctx == nullptr) {
        log_message("ERROR", "Failed to initialize Whisper model");
        env->ReleaseStringUTFChars(modelPath_, modelPath);
        return;
    }

    // Инициализируем параметры транскрибации
    g_wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    g_wparams.print_progress = false;
    g_wparams.print_realtime = false;
    g_wparams.print_timestamps = false;
    g_wparams.translate = false;
    g_params.language = "auto";
    g_wparams.n_threads = 4; // Установи количество потоков по необходимости
    g_wparams.audio_ctx = 0; // 0 = default
    g_wparams.speed_up = false;
    g_wparams.token_timestamps = false;
    g_wparams.suppress_non_speech_tokens = false;
    g_wparams.temperature = 0.0f;
    g_wparams.max_len = 0;
    g_wparams.split_on_word = false;

    // Устанавливаем no_context в false для стриминга
    g_wparams.no_context = false;

    env->ReleaseStringUTFChars(modelPath_, modelPath);

    log_message("INFO", "Model loaded successfully");
}

// JNI: Загрузка аудио чанка в очередь
extern "C"
JNIEXPORT void JNICALL
Java_com_example_whisperkotlin_WhisperTranscriber_loadAudioChunk(JNIEnv *env, jobject thiz, jfloatArray audioData) {
    jsize len = env->GetArrayLength(audioData);
    jfloat* audioPtr = env->GetFloatArrayElements(audioData, nullptr);

    std::vector<float> chunk(audioPtr, audioPtr + len);
    env->ReleaseFloatArrayElements(audioData, audioPtr, JNI_ABORT);

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_audio_queue.push(chunk);
    }
    g_cv.notify_one();
}

// JNI: Запуск обработки аудио (в отдельном потоке)
extern "C"
JNIEXPORT void JNICALL
Java_com_example_whisperkotlin_WhisperTranscriber_startProcessing(JNIEnv *env, jobject thiz) {
    g_stop_flag = false;
    std::thread worker_thread(audio_worker);
    worker_thread.detach(); // Отсоединяем поток, он работает до остановки
}

// JNI: Остановка обработки
extern "C"
JNIEXPORT void JNICALL
Java_com_example_whisperkotlin_WhisperTranscriber_stopProcessing(JNIEnv *env, jobject thiz) {
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_flag = true;
    }
    g_cv.notify_all();
}

// JNI: Получение текущего транскрибированного текста (промпта)
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_whisperkotlin_WhisperTranscriber_getCurrentText(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_prompt_buffer.c_str());
}

// JNI: Очистка модели
extern "C"
JNIEXPORT void JNICALL
Java_com_example_whisperkotlin_WhisperTranscriber_releaseModel(JNIEnv *env, jobject thiz) {
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
    // Очищаем глобальные переменные
    g_prompt_buffer.clear();
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        while (!g_audio_queue.empty()) {
            g_audio_queue.pop();
        }
        g_stop_flag = true;
    }
    g_cv.notify_all();
    log_message("INFO", "Model released");
}