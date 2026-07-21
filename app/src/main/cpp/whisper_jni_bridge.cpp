#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static whisper_context_params cparams = whisper_context_default_params();
static whisper_context* ctx = nullptr;
static std::string current_model_path = "";

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_MainActivity_transcribeAudio(
        JNIEnv* env, jobject /* this */, jstring audioPath, jstring modelPath, jstring language) {

    const char* model_path_str = env->GetStringUTFChars(modelPath, nullptr);
    const char* audio_path_str = env->GetStringUTFChars(audioPath, nullptr);
    const char* lang_str = env->GetStringUTFChars(language, nullptr);

    if (current_model_path != model_path_str || ctx == nullptr) {
        if (ctx != nullptr) {
            whisper_free(ctx);
        }
        ctx = whisper_init_from_file_with_params(model_path_str, cparams);
        if (ctx == nullptr) {
            LOGE("Failed to initialize whisper context from: %s", model_path_str);
            env->ReleaseStringUTFChars(modelPath, model_path_str);
            env->ReleaseStringUTFChars(audioPath, audio_path_str);
            env->ReleaseStringUTFChars(language, lang_str);
            return env->NewStringUTF("Ошибка: модель не найдена или повреждена");
        }
        current_model_path = model_path_str;
        LOGI("Model loaded successfully");
    }

    std::vector<float> pcmf32;
    std::ifstream file(audio_path_str, std::ios::binary);
    if (!file) {
        LOGE("Failed to open audio file: %s", audio_path_str);
        env->ReleaseStringUTFChars(modelPath, model_path_str);
        env->ReleaseStringUTFChars(audioPath, audio_path_str);
        env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("Ошибка: не удалось открыть аудиофайл");
    }

    file.seekg(0, std::ios::end);
    size_t file_size = file.tellg();
    file.seekg(44, std::ios::beg); // Пропускаем стандартный WAV-заголовок

    size_t num_samples = (file_size - 44) / 2;
    if (num_samples == 0) {
        env->ReleaseStringUTFChars(modelPath, model_path_str);
        env->ReleaseStringUTFChars(audioPath, audio_path_str);
        env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("Аудиофайл пуст");
    }

    pcmf32.resize(num_samples);
    std::vector<int16_t> pcm16(num_samples);
    file.read(reinterpret_cast<char*>(pcm16.data()), num_samples * 2);
    file.close();

    for (size_t i = 0; i < num_samples; i++) {
        pcmf32[i] = pcm16[i] / 32768.0f;
    }

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = lang_str;
    wparams.n_threads = 4;

    if (whisper_full(ctx, wparams, pcmf32.data(), pcmf32.size()) != 0) {
        LOGE("whisper_full failed");
        env->ReleaseStringUTFChars(modelPath, model_path_str);
        env->ReleaseStringUTFChars(audioPath, audio_path_str);
        env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("Ошибка распознавания");
    }

    int n_segments = whisper_full_n_segments(ctx);
    std::string result = "";
    for (int i = 0; i < n_segments; ++i) {
        result += whisper_full_get_segment_text(ctx, i);
    }

    env->ReleaseStringUTFChars(modelPath, model_path_str);
    env->ReleaseStringUTFChars(audioPath, audio_path_str);
    env->ReleaseStringUTFChars(language, lang_str);

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_MainActivity_transcribeChunk(
        JNIEnv* env, jobject /* this */, jbyteArray audioData, jstring modelPath, jstring language) {

    const char* model_path_str = env->GetStringUTFChars(modelPath, nullptr);
    const char* lang_str = env->GetStringUTFChars(language, nullptr);

    if (current_model_path != model_path_str || ctx == nullptr) {
        if (ctx != nullptr) {
            whisper_free(ctx);
        }
        ctx = whisper_init_from_file_with_params(model_path_str, cparams);
        if (ctx == nullptr) {
            LOGE("Failed to initialize whisper context from: %s", model_path_str);
            env->ReleaseStringUTFChars(modelPath, model_path_str);
            env->ReleaseStringUTFChars(language, lang_str);
            return env->NewStringUTF("Ошибка инициализации модели");
        }
        current_model_path = model_path_str;
        LOGI("Model loaded successfully");
    }

    jsize len = env->GetArrayLength(audioData);
    if (len < 32000) { // Меньше 1 секунды аудио (16000 Гц * 2 байта)
        env->ReleaseStringUTFChars(modelPath, model_path_str);
        env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("");
    }

    jbyte* bytes = env->GetByteArrayElements(audioData, nullptr);
    size_t num_samples = len / 2;
    std::vector<float> pcmf32(num_samples);
    int16_t* pcm16 = reinterpret_cast<int16_t*>(bytes);

    for (size_t i = 0; i < num_samples; i++) {
        pcmf32[i] = pcm16[i] / 32768.0f;
    }

    env->ReleaseByteArrayElements(audioData, bytes, JNI_ABORT);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = lang_str;
    wparams.n_threads = 2;
    wparams.no_context = true; // Критично для чанков, чтобы не галлюцинировать на основе прошлого

    if (whisper_full(ctx, wparams, pcmf32.data(), pcmf32.size()) != 0) {
        LOGE("whisper_full failed for chunk");
        env->ReleaseStringUTFChars(modelPath, model_path_str);
        env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("Ошибка распознавания чанка");
    }

    int n_segments = whisper_full_n_segments(ctx);
    std::string result = "";
    for (int i = 0; i < n_segments; ++i) {
        result += whisper_full_get_segment_text(ctx, i);
    }

    env->ReleaseStringUTFChars(modelPath, model_path_str);
    env->ReleaseStringUTFChars(language, lang_str);

    return env->NewStringUTF(result.c_str());
}