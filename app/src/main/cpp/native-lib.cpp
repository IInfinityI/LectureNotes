#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <mutex>
#include <fstream>
#include <cstdlib>
#include <cstring>

// Подключаем заголовки Whisper.cpp
#include "whisper.h"

#define LOG_TAG "WhisperNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Глобальный контекст модели (кэш) – загружается один раз
static whisper_context* g_whisper_ctx = nullptr;
static std::mutex g_whisper_mutex;

// Путь к файлу модели (запоминаем, чтобы при повторном вызове не копировать)
static std::string g_model_path;

// Вспомогательная функция: скопировать модель из assets во внутреннее хранилище
// (эта функция уже была в исходном коде, оставляем как есть)
static bool copy_model_from_assets(JNIEnv* env, jobject context, const char* asset_name, const char* dest_path) {
    // Получаем AssetManager
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getAssetsMethod = env->GetMethodID(contextClass, "getAssets", "()Landroid/content/res/AssetManager;");
    jobject assetManager = env->CallObjectMethod(context, getAssetsMethod);

    jclass assetManagerClass = env->GetObjectClass(assetManager);
    jmethodID openMethod = env->GetMethodID(assetManagerClass, "open", "(Ljava/lang/String;)Ljava/io/InputStream;");
    jstring assetNameStr = env->NewStringUTF(asset_name);
    jobject inputStream = env->CallObjectMethod(assetManager, openMethod, assetNameStr);
    env->DeleteLocalRef(assetNameStr);

    if (inputStream == nullptr) {
        LOGE("Failed to open asset: %s", asset_name);
        return false;
    }

    // Читаем InputStream в память
    jclass inputStreamClass = env->GetObjectClass(inputStream);
    jmethodID readMethod = env->GetMethodID(inputStreamClass, "read", "([B)I");
    jmethodID closeMethod = env->GetMethodID(inputStreamClass, "close", "()V");

    std::vector<uint8_t> buffer;
    jbyteArray byteArray = env->NewByteArray(8192);
    int bytesRead;
    while ((bytesRead = env->CallIntMethod(inputStream, readMethod, byteArray)) > 0) {
        jbyte* elements = env->GetByteArrayElements(byteArray, nullptr);
        buffer.insert(buffer.end(), elements, elements + bytesRead);
        env->ReleaseByteArrayElements(byteArray, elements, JNI_ABORT);
    }
    env->DeleteLocalRef(byteArray);
    env->CallVoidMethod(inputStream, closeMethod);
    env->DeleteLocalRef(inputStream);

    // Записываем в файл
    std::ofstream out(dest_path, std::ios::binary);
    if (!out.is_open()) {
        LOGE("Failed to create file: %s", dest_path);
        return false;
    }
    out.write(reinterpret_cast<const char*>(buffer.data()), buffer.size());
    out.close();
    LOGI("Model copied to: %s", dest_path);
    return true;
}

// Инициализация модели (вызывается один раз)
static bool init_whisper_model(const char* model_path) {
    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    if (g_whisper_ctx != nullptr) {
        LOGI("Model already loaded");
        return true;
    }

    // Проверяем существование файла
    std::ifstream f(model_path);
    if (!f.good()) {
        LOGE("Model file not found: %s", model_path);
        return false;
    }
    f.close();

    // Загружаем модель
    g_whisper_ctx = whisper_init_from_file(model_path);
    if (g_whisper_ctx == nullptr) {
        LOGE("Failed to initialize Whisper model from: %s", model_path);
        return false;
    }

    LOGI("Whisper model loaded successfully from: %s", model_path);
    g_model_path = model_path;
    return true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_transcription_TranscriptionManager_transcribeChunk(
        JNIEnv* env,
        jobject /* this */,
        jobject context,
        jbyteArray audioData,
        jint sampleRate,
        jstring modelAssetName,
        jstring language) {

    // Преобразуем jstring в C-строки
    const char* modelAsset = env->GetStringUTFChars(modelAssetName, nullptr);
    const char* lang = env->GetStringUTFChars(language, nullptr);

    // Получаем путь к внутреннему хранилищу приложения
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getFilesDirMethod = env->GetMethodID(contextClass, "getFilesDir", "()Ljava/io/File;");
    jobject filesDir = env->CallObjectMethod(context, getFilesDirMethod);
    jclass fileClass = env->GetObjectClass(filesDir);
    jmethodID getAbsolutePathMethod = env->GetMethodID(fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    jstring pathStr = (jstring)env->CallObjectMethod(filesDir, getAbsolutePathMethod);
    const char* filesDirPath = env->GetStringUTFChars(pathStr, nullptr);

    // Формируем путь к модели во внутреннем хранилище
    std::string modelPath = std::string(filesDirPath) + "/" + modelAsset;
    env->ReleaseStringUTFChars(pathStr, filesDirPath);
    env->DeleteLocalRef(pathStr);
    env->DeleteLocalRef(filesDir);

    // Копируем модель из assets, если её ещё нет
    std::ifstream testModel(modelPath);
    if (!testModel.good()) {
        LOGI("Model not found in internal storage, copying from assets...");
        if (!copy_model_from_assets(env, context, modelAsset, modelPath.c_str())) {
            LOGE("Failed to copy model from assets");
            env->ReleaseStringUTFChars(modelAssetName, modelAsset);
            env->ReleaseStringUTFChars(language, lang);
            return env->NewStringUTF("");
        }
    } else {
        testModel.close();
    }

    // Инициализируем модель (если ещё не инициализирована)
    if (!init_whisper_model(modelPath.c_str())) {
        LOGE("Model initialization failed");
        env->ReleaseStringUTFChars(modelAssetName, modelAsset);
        env->ReleaseStringUTFChars(language, lang);
        return env->NewStringUTF("");
    }

    // Получаем аудиоданные из jbyteArray
    jsize len = env->GetArrayLength(audioData);
    jbyte* audioBytes = env->GetByteArrayElements(audioData, nullptr);
    if (len == 0 || audioBytes == nullptr) {
        LOGE("Audio data is empty");
        env->ReleaseByteArrayElements(audioData, audioBytes, JNI_ABORT);
        env->ReleaseStringUTFChars(modelAssetName, modelAsset);
        env->ReleaseStringUTFChars(language, lang);
        return env->NewStringUTF("");
    }

    // Конвертируем байты в float (16-bit PCM -> float)
    int16_t* pcm16 = reinterpret_cast<int16_t*>(audioBytes);
    int numSamples = len / sizeof(int16_t);
    std::vector<float> pcmF32(numSamples);
    for (int i = 0; i < numSamples; i++) {
        pcmF32[i] = pcm16[i] / 32768.0f;
    }
    env->ReleaseByteArrayElements(audioData, audioBytes, JNI_ABORT);

    // Транскрибируем с использованием кэшированной модели
    std::string result;
    {
        // Блокируем мьютекс, так как модель может использоваться из разных потоков
        std::lock_guard<std::mutex> lock(g_whisper_mutex);

        // Настройки Whisper
        whisper_params params;
        params.language = lang;
        params.n_threads = 4;       // можно увеличить для скорости
        params.translate = false;
        params.no_context = true;   // не используем контекст между чанками

        // Запускаем транскрипцию
        if (whisper_full(g_whisper_ctx, params, pcmF32.data(), numSamples) != 0) {
            LOGE("Whisper transcription failed");
            env->ReleaseStringUTFChars(modelAssetName, modelAsset);
            env->ReleaseStringUTFChars(language, lang);
            return env->NewStringUTF("");
        }

        // Получаем результат
        int n_segments = whisper_full_n_segments(g_whisper_ctx);
        for (int i = 0; i < n_segments; ++i) {
            const char* text = whisper_full_get_segment_text(g_whisper_ctx, i);
            if (text != nullptr) {
                result += text;
                result += " ";
            }
        }
    }

    // Освобождаем строки
    env->ReleaseStringUTFChars(modelAssetName, modelAsset);
    env->ReleaseStringUTFChars(language, lang);

    // Возвращаем транскрипцию
    return env->NewStringUTF(result.c_str());
}

// Освобождение модели при завершении (опционально, но для чистоты)
extern "C" JNIEXPORT void JNICALL
Java_com_example_lecturenotes_transcription_TranscriptionManager_releaseModel(
        JNIEnv* /* env */,
        jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    if (g_whisper_ctx != nullptr) {
        whisper_free(g_whisper_ctx);
        g_whisper_ctx = nullptr;
        LOGI("Whisper model released");
    }
}