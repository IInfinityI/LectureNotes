#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <fstream>
#include <mutex>
#include <memory>
#include <unordered_map>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================================
// ПУЛ МОДЕЛЕЙ (Приоритет 3: двухпоточная архитектура)
//
// Каждая модель — отдельный whisper_context с собственным mutex.
// Стриминг (tiny) и финальная транскрибация (base) не блокируют друг друга.
//
// НОВЫЙ API: nativeInitModel / nativeTranscribeChunkWithModel /
//            nativeTranscribeAudioWithModel / nativeReleaseModelByPath /
//            nativeReleaseAllModels / nativeIsModelLoaded
//
// СТАРЫЙ API (initModel / releaseModel / transcribeChunk / transcribeAudio)
// сохранён для обратной совместимости и работает с g_default_model_path.
// ============================================================================

struct ModelHandle {
    whisper_context* ctx = nullptr;
    std::mutex mtx;
};

using ModelHandlePtr = std::shared_ptr<ModelHandle>;

static std::unordered_map<std::string, ModelHandlePtr> g_models;
static std::mutex g_pool_mutex;
static std::string g_default_model_path = "";

// Возвращает shared_ptr: модель не умрёт под ногами у активной транскрибации,
// даже если её удалят из пула во время работы.
static ModelHandlePtr find_model(const std::string& path) {
    std::lock_guard<std::mutex> lock(g_pool_mutex);
    auto it = g_models.find(path);
    return (it != g_models.end()) ? it->second : nullptr;
}

static bool ensure_model_loaded(const std::string& path) {
    {
        std::lock_guard<std::mutex> lock(g_pool_mutex);
        if (g_models.find(path) != g_models.end()) {
            return true;
        }
    }

    whisper_context_params cparams = whisper_context_default_params();
    whisper_context* new_ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (new_ctx == nullptr) {
        LOGE("Failed to initialize whisper context from: %s", path.c_str());
        return false;
    }

    std::lock_guard<std::mutex> lock(g_pool_mutex);
    auto it = g_models.find(path);
    if (it != g_models.end()) {
        // Модель загрузили параллельно — освобождаем дубликат
        whisper_free(new_ctx);
        LOGI("Model already loaded by another thread: %s", path.c_str());
        return true;
    }

    auto handle = std::make_shared<ModelHandle>();
    handle->ctx = new_ctx;
    g_models[path] = handle;
    LOGI("Model loaded: %s", path.c_str());
    return true;
}

static void release_model(const std::string& path) {
    ModelHandlePtr handle;
    {
        std::lock_guard<std::mutex> lock(g_pool_mutex);
        auto it = g_models.find(path);
        if (it == g_models.end()) return;
        handle = it->second;
        g_models.erase(it);
        if (g_default_model_path == path) {
            g_default_model_path = "";
        }
    }
    // Ждём завершения активной транскрибации перед освобождением
    std::lock_guard<std::mutex> hlock(handle->mtx);
    if (handle->ctx != nullptr) {
        whisper_free(handle->ctx);
        handle->ctx = nullptr;
    }
    LOGI("Model released: %s", path.c_str());
}

static std::vector<float> pcm16_to_float32(const int16_t* data, size_t count) {
    std::vector<float> out(count);
    for (size_t i = 0; i < count; ++i) {
        out[i] = static_cast<float>(data[i]) / 32768.0f;
    }
    return out;
}

// Внимание: вызывающий код ОБЯЗАН держать handle->mtx
static std::string run_transcription(
        ModelHandle* handle,
        const float* samples,
        int n_samples,
        const char* language,
        int n_threads,
        bool single_segment) {

    if (handle->ctx == nullptr) {
        LOGE("run_transcription: context is null");
        return "";
    }

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = language;
    wparams.n_threads = n_threads;
    wparams.no_context = false;
    wparams.single_segment = single_segment;
    wparams.no_timestamps = single_segment;

    if (whisper_full(handle->ctx, wparams, samples, n_samples) != 0) {
        LOGE("whisper_full failed");
        return "";
    }

    int n_segments = whisper_full_n_segments(handle->ctx);
    std::string result;
    result.reserve(256);
    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(handle->ctx, i);
        if (text != nullptr) {
            result += text;
        }
    }
    return result;
}

// ----------------------------------------------------------------------------
// НОВЫЙ API: явный выбор модели
// ----------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_nativeInitModel(
        JNIEnv* env, jobject, jstring modelPath) {

    if (modelPath == nullptr) {
        LOGE("nativeInitModel: modelPath is null");
        return JNI_FALSE;
    }

    const char* path_str = env->GetStringUTFChars(modelPath, nullptr);
    if (path_str == nullptr) return JNI_FALSE;

    bool ok = ensure_model_loaded(std::string(path_str));

    env->ReleaseStringUTFChars(modelPath, path_str);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_nativeIsModelLoaded(
        JNIEnv* env, jobject, jstring modelPath) {

    if (modelPath == nullptr) return JNI_FALSE;

    const char* path_str = env->GetStringUTFChars(modelPath, nullptr);
    if (path_str == nullptr) return JNI_FALSE;

    bool loaded = (find_model(std::string(path_str)) != nullptr);

    env->ReleaseStringUTFChars(modelPath, path_str);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_nativeReleaseModelByPath(
        JNIEnv* env, jobject, jstring modelPath) {

    if (modelPath == nullptr) return;

    const char* path_str = env->GetStringUTFChars(modelPath, nullptr);
    if (path_str == nullptr) return;

    release_model(std::string(path_str));

    env->ReleaseStringUTFChars(modelPath, path_str);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_nativeReleaseAllModels(
        JNIEnv* env, jobject) {

    std::vector<std::string> paths;
    {
        std::lock_guard<std::mutex> lock(g_pool_mutex);
        paths.reserve(g_models.size());
        for (const auto& kv : g_models) {
            paths.push_back(kv.first);
        }
    }
    for (const auto& p : paths) {
        release_model(p);
    }
    LOGI("All models released");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_nativeTranscribeChunkWithModel(
        JNIEnv* env, jobject, jbyteArray audioData, jstring language, jstring modelPath) {

    if (audioData == nullptr || language == nullptr || modelPath == nullptr) {
        LOGE("nativeTranscribeChunkWithModel: null argument");
        return env->NewStringUTF("");
    }

    const char* lang_str = env->GetStringUTFChars(language, nullptr);
    const char* path_str = env->GetStringUTFChars(modelPath, nullptr);
    if (lang_str == nullptr || path_str == nullptr) {
        if (lang_str) env->ReleaseStringUTFChars(language, lang_str);
        if (path_str) env->ReleaseStringUTFChars(modelPath, path_str);
        return env->NewStringUTF("");
    }

    std::string result;

    do {
        jsize len = env->GetArrayLength(audioData);
        if (len < 32000) {
            LOGI("Chunk too short (%d bytes), skipping", len);
            break;
        }

        ModelHandlePtr handle = find_model(std::string(path_str));
        if (handle == nullptr) {
            LOGE("Model not loaded: %s", path_str);
            break;
        }

        jbyte* bytes = env->GetByteArrayElements(audioData, nullptr);
        if (bytes == nullptr) break;

        size_t num_samples = static_cast<size_t>(len) / 2;
        std::vector<float> pcmf32 = pcm16_to_float32(
                reinterpret_cast<int16_t*>(bytes), num_samples);

        env->ReleaseByteArrayElements(audioData, bytes, JNI_ABORT);

        std::lock_guard<std::mutex> hlock(handle->mtx);
        result = run_transcription(handle.get(), pcmf32.data(),
                                   static_cast<int>(pcmf32.size()),
                                   lang_str, 4, true);
    } while (false);

    env->ReleaseStringUTFChars(language, lang_str);
    env->ReleaseStringUTFChars(modelPath, path_str);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_nativeTranscribeAudioWithModel(
        JNIEnv* env, jobject, jstring audioPath, jstring language, jstring modelPath) {

    if (audioPath == nullptr || language == nullptr || modelPath == nullptr) {
        LOGE("nativeTranscribeAudioWithModel: null argument");
        return env->NewStringUTF("");
    }

    const char* audio_path_str = env->GetStringUTFChars(audioPath, nullptr);
    const char* lang_str = env->GetStringUTFChars(language, nullptr);
    const char* model_path_str = env->GetStringUTFChars(modelPath, nullptr);

    if (audio_path_str == nullptr || lang_str == nullptr || model_path_str == nullptr) {
        if (audio_path_str) env->ReleaseStringUTFChars(audioPath, audio_path_str);
        if (lang_str) env->ReleaseStringUTFChars(language, lang_str);
        if (model_path_str) env->ReleaseStringUTFChars(modelPath, model_path_str);
        return env->NewStringUTF("");
    }

    std::string result;

    do {
        ModelHandlePtr handle = find_model(std::string(model_path_str));
        if (handle == nullptr) {
            LOGE("Model not loaded: %s", model_path_str);
            break;
        }

        std::ifstream file(audio_path_str, std::ios::binary | std::ios::ate);
        if (!file) {
            LOGE("Failed to open audio file: %s", audio_path_str);
            break;
        }

        size_t file_size = static_cast<size_t>(file.tellg());
        file.seekg(0, std::ios::beg);

        if (file_size < 32000) {
            LOGE("Audio file too small: %zu bytes", file_size);
            break;
        }

        size_t num_samples = file_size / 2;
        std::vector<int16_t> pcm16(num_samples);
        file.read(reinterpret_cast<char*>(pcm16.data()),
                  static_cast<std::streamsize>(file_size));
        file.close();

        std::vector<float> pcmf32 = pcm16_to_float32(pcm16.data(), num_samples);

        std::lock_guard<std::mutex> hlock(handle->mtx);
        result = run_transcription(handle.get(), pcmf32.data(),
                                   static_cast<int>(pcmf32.size()),
                                   lang_str, 4, false);
    } while (false);

    env->ReleaseStringUTFChars(audioPath, audio_path_str);
    env->ReleaseStringUTFChars(language, lang_str);
    env->ReleaseStringUTFChars(modelPath, model_path_str);
    return env->NewStringUTF(result.c_str());
}

// ----------------------------------------------------------------------------
// СТАРЫЙ API (обратная совместимость, использует g_default_model_path)
// ----------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_initModel(
        JNIEnv* env, jobject, jstring modelPath) {

    if (modelPath == nullptr) {
        LOGE("initModel: modelPath is null");
        return JNI_FALSE;
    }

    const char* path_str = env->GetStringUTFChars(modelPath, nullptr);
    if (path_str == nullptr) return JNI_FALSE;

    bool ok = ensure_model_loaded(std::string(path_str));
    if (ok) {
        g_default_model_path = std::string(path_str);
    }

    env->ReleaseStringUTFChars(modelPath, path_str);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_releaseModel(
        JNIEnv* env, jobject) {

    std::string path;
    {
        std::lock_guard<std::mutex> lock(g_pool_mutex);
        path = g_default_model_path;
    }
    if (!path.empty()) {
        release_model(path);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_transcribeChunk(
        JNIEnv* env, jobject, jbyteArray audioData, jstring language) {

    if (audioData == nullptr || language == nullptr) {
        return env->NewStringUTF("");
    }

    const char* lang_str = env->GetStringUTFChars(language, nullptr);
    if (lang_str == nullptr) return env->NewStringUTF("");

    std::string result;

    do {
        std::string default_path;
        {
            std::lock_guard<std::mutex> lock(g_pool_mutex);
            default_path = g_default_model_path;
        }
        if (default_path.empty()) {
            LOGE("Default model not set. Call initModel() first");
            break;
        }

        jsize len = env->GetArrayLength(audioData);
        if (len < 32000) break;

        ModelHandlePtr handle = find_model(default_path);
        if (handle == nullptr) break;

        jbyte* bytes = env->GetByteArrayElements(audioData, nullptr);
        if (bytes == nullptr) break;

        size_t num_samples = static_cast<size_t>(len) / 2;
        std::vector<float> pcmf32 = pcm16_to_float32(
                reinterpret_cast<int16_t*>(bytes), num_samples);

        env->ReleaseByteArrayElements(audioData, bytes, JNI_ABORT);

        std::lock_guard<std::mutex> hlock(handle->mtx);
        result = run_transcription(handle.get(), pcmf32.data(),
                                   static_cast<int>(pcmf32.size()),
                                   lang_str, 4, true);
    } while (false);

    env->ReleaseStringUTFChars(language, lang_str);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_transcribeAudio(
        JNIEnv* env, jobject, jstring audioPath, jstring language) {

    if (audioPath == nullptr || language == nullptr) {
        return env->NewStringUTF("");
    }

    const char* audio_path_str = env->GetStringUTFChars(audioPath, nullptr);
    const char* lang_str = env->GetStringUTFChars(language, nullptr);

    if (audio_path_str == nullptr || lang_str == nullptr) {
        if (audio_path_str) env->ReleaseStringUTFChars(audioPath, audio_path_str);
        if (lang_str) env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("");
    }

    std::string result;

    do {
        std::string default_path;
        {
            std::lock_guard<std::mutex> lock(g_pool_mutex);
            default_path = g_default_model_path;
        }
        if (default_path.empty()) {
            LOGE("Default model not set. Call initModel() first");
            break;
        }

        ModelHandlePtr handle = find_model(default_path);
        if (handle == nullptr) break;

        std::ifstream file(audio_path_str, std::ios::binary | std::ios::ate);
        if (!file) {
            LOGE("Failed to open audio file: %s", audio_path_str);
            break;
        }

        size_t file_size = static_cast<size_t>(file.tellg());
        file.seekg(0, std::ios::beg);

        if (file_size < 32000) break;

        size_t num_samples = file_size / 2;
        std::vector<int16_t> pcm16(num_samples);
        file.read(reinterpret_cast<char*>(pcm16.data()),
                  static_cast<std::streamsize>(file_size));
        file.close();

        std::vector<float> pcmf32 = pcm16_to_float32(pcm16.data(), num_samples);

        std::lock_guard<std::mutex> hlock(handle->mtx);
        result = run_transcription(handle.get(), pcmf32.data(),
                                   static_cast<int>(pcmf32.size()),
                                   lang_str, 4, false);
    } while (false);

    env->ReleaseStringUTFChars(audioPath, audio_path_str);
    env->ReleaseStringUTFChars(language, lang_str);
    return env->NewStringUTF(result.c_str());
}