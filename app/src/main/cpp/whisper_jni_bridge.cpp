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
            return env->NewStringUTF("Ошибка: модель не найдена");
        }
        current_model_path = model_path_str;
        LOGI("Model loaded successfully");
    }

    std::ifstream file(audio_path_str, std::ios::binary | std::ios::ate);
    if (!file) {
        LOGE("Failed to open audio file: %s", audio_path_str);
        env->ReleaseStringUTFChars(modelPath, model_path_str);
        env->ReleaseStringUTFChars(audioPath, audio_path_str);
        env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("Ошибка: не удалось открыть аудиофайл");
    }

    size_t file_size = file.tellg();
    file.seekg(0, std::ios::beg);

    size_t num_samples = file_size / 2; // 16-bit PCM
    if (num_samples == 0) {
        env->ReleaseStringUTFChars(modelPath, model_path_str);
        env->ReleaseStringUTFChars(audioPath, audio_path_str);
        env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("Аудиофайл пуст");
    }

    std::vector<int16_t> pcm16(num_samples);
    file.read(reinterpret_cast<char*>(pcm16.data()), file_size);
    file.close();

    std::vector<float> pcmf32(num_samples);
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
    if (len < 32000) {
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
    wparams.no_context = true;

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