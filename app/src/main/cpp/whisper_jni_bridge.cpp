#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <fstream>
#include <whisper.h>

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Глобальное состояние модели
static whisper_context* ctx = nullptr;
static std::string current_model_path = "";

// Инициализация модели (вызывается один раз при старте)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_initModel(
    JNIEnv* env, jobject /* this */, jstring modelPath) {
    
    const char* model_path_str = env->GetStringUTFChars(modelPath, nullptr);
    
    // Если модель уже загружена и путь не изменился - просто возвращаем true
    if (ctx != nullptr && current_model_path == model_path_str) {
        LOGI("Model already loaded from: %s", model_path_str);
        env->ReleaseStringUTFChars(modelPath, model_path_str);
        return JNI_TRUE;
    }
    
    // Освобождаем старую модель, если она была
    if (ctx != nullptr) {
        whisper_free(ctx);
        ctx = nullptr;
        current_model_path = "";
    }
    
    // Загружаем новую модель
    whisper_context_params cparams = whisper_context_default_params();
    ctx = whisper_init_from_file_with_params(model_path_str, cparams);
    
    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper context from: %s", model_path_str);
        env->ReleaseStringUTFChars(modelPath, model_path_str);
        return JNI_FALSE;
    }
    
    current_model_path = model_path_str;
    LOGI("Model loaded successfully from: %s", model_path_str);
    
    env->ReleaseStringUTFChars(modelPath, model_path_str);
    return JNI_TRUE;
}

// Освобождение модели (вызывается при закрытии приложения)
extern "C" JNIEXPORT void JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_releaseModel(
    JNIEnv* env, jobject /* this */) {
    
    if (ctx != nullptr) {
        whisper_free(ctx);
        ctx = nullptr;
        current_model_path = "";
        LOGI("Model released");
    }
}

// Транскрибация аудио-чанка в реальном времени
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_transcribeChunk(
    JNIEnv* env, jobject /* this */, jbyteArray audioData, jstring language) {
    
    if (ctx == nullptr) {
        LOGE("Model not initialized! Call initModel() first");
        return env->NewStringUTF("");
    }
    
    const char* lang_str = env->GetStringUTFChars(language, nullptr);
    
    jsize len = env->GetArrayLength(audioData);
    if (len < 32000) { // Минимум 1 секунда аудио (16000 samples * 2 bytes)
        env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("");
    }
    
    // Конвертируем PCM 16-bit в float32
    jbyte* bytes = env->GetByteArrayElements(audioData, nullptr);
    size_t num_samples = len / 2;
    std::vector<float> pcmf32(num_samples);
    int16_t* pcm16 = reinterpret_cast<int16_t*>(bytes);
    
    for (size_t i = 0; i < num_samples; i++) {
        pcmf32[i] = pcm16[i] / 32768.0f;
    }
    
    env->ReleaseByteArrayElements(audioData, bytes, JNI_ABORT);
    
    // Настройки транскрибации
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = lang_str;
    wparams.n_threads = 2;
    wparams.no_context = true; // Для чанков не используем контекст предыдущих
    
    if (whisper_full(ctx, wparams, pcmf32.data(), pcmf32.size()) != 0) {
        LOGE("whisper_full failed for chunk");
        env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("");
    }
    
    // Собираем результат
    int n_segments = whisper_full_n_segments(ctx);
    std::string result = "";
    for (int i = 0; i < n_segments; ++i) {
        result += whisper_full_get_segment_text(ctx, i);
    }
    
    env->ReleaseStringUTFChars(language, lang_str);
    return env->NewStringUTF(result.c_str());
}

// Транскрибация полного аудиофайла
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_transcription_WhisperTranscriber_transcribeAudio(
    JNIEnv* env, jobject /* this */, jstring audioPath, jstring language) {
    
    if (ctx == nullptr) {
        LOGE("Model not initialized! Call initModel() first");
        return env->NewStringUTF("");
    }
    
    const char* audio_path_str = env->GetStringUTFChars(audioPath, nullptr);
    const char* lang_str = env->GetStringUTFChars(language, nullptr);
    
    // Читаем аудиофайл
    std::ifstream file(audio_path_str, std::ios::binary | std::ios::ate);
    if (!file) {
        LOGE("Failed to open audio file: %s", audio_path_str);
        env->ReleaseStringUTFChars(audioPath, audio_path_str);
        env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("");
    }
    
    size_t file_size = file.tellg();
    file.seekg(0, std::ios::beg);
    
    size_t num_samples = file_size / 2; // 16-bit PCM
    if (num_samples == 0) {
        env->ReleaseStringUTFChars(audioPath, audio_path_str);
        env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("");
    }
    
    std::vector<int16_t> pcm16(num_samples);
    file.read(reinterpret_cast<char*>(pcm16.data()), file_size);
    file.close();
    
    // Конвертируем в float32
    std::vector<float> pcmf32(num_samples);
    for (size_t i = 0; i < num_samples; i++) {
        pcmf32[i] = pcm16[i] / 32768.0f;
    }
    
    // Настройки транскрибации
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = lang_str;
    wparams.n_threads = 4; // Больше потоков для полного файла
    wparams.no_context = false; // Используем контекст для связного текста
    
    if (whisper_full(ctx, wparams, pcmf32.data(), pcmf32.size()) != 0) {
        LOGE("whisper_full failed for file");
        env->ReleaseStringUTFChars(audioPath, audio_path_str);
        env->ReleaseStringUTFChars(language, lang_str);
        return env->NewStringUTF("");
    }
    
    // Собираем результат
    int n_segments = whisper_full_n_segments(ctx);
    std::string result = "";
    for (int i = 0; i < n_segments; ++i) {
        result += whisper_full_get_segment_text(ctx, i);
    }
    
    env->ReleaseStringUTFChars(audioPath, audio_path_str);
    env->ReleaseStringUTFChars(language, lang_str);
    return env->NewStringUTF(result.c_str());
}