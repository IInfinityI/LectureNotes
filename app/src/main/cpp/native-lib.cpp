#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <android/log.h>
#include "whisper.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LectureNotes", __VA_ARGS__)

// онвертация UTF-8 → UTF-16 (нативный формат Java/Kotlin строк)
static jstring utf8ToJstring(JNIEnv* env, const std::string& utf8) {
    if (utf8.empty()) return env->NewStringUTF("");
    
    std::u16string utf16;
    for (size_t i = 0; i < utf8.size(); ) {
        uint32_t cp = 0;
        unsigned char c = utf8[i++];
        
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0) {
            cp = (c & 0x1F) << 6;
            if (i < utf8.size()) cp |= utf8[i++] & 0x3F;
        } else if ((c & 0xF0) == 0xE0) {
            cp = (c & 0x0F) << 12;
            if (i < utf8.size()) cp |= (utf8[i++] & 0x3F) << 6;
            if (i < utf8.size()) cp |= utf8[i++] & 0x3F;
        } else {
            cp = (c & 0x07) << 18;
            if (i < utf8.size()) cp |= (utf8[i++] & 0x3F) << 12;
            if (i < utf8.size()) cp |= (utf8[i++] & 0x3F) << 6;
            if (i < utf8.size()) cp |= utf8[i++] & 0x3F;
        }
        
        if (cp <= 0xFFFF) {
            utf16 += static_cast<char16_t>(cp);
        } else {
            cp -= 0x10000;
            utf16 += static_cast<char16_t>(0xD800 | (cp >> 10));
            utf16 += static_cast<char16_t>(0xDC00 | (cp & 0x3FF));
        }
    }
    
    return env->NewString(reinterpret_cast<const jchar*>(utf16.c_str()), utf16.length());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_MainActivity_stringFromJNI(JNIEnv* env, jobject) {
    return env->NewStringUTF("Whisper.cpp loaded");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_MainActivity_transcribeChunk(
    JNIEnv* env, jobject, jbyteArray audio_data, jstring model_path, jstring language) {
    
    jsize length = env->GetArrayLength(audio_data);
    if (length < 32000) return env->NewStringUTF("");
    
    jbyte* buffer = env->GetByteArrayElements(audio_data, 0);
    short* pcm_s16 = reinterpret_cast<short*>(buffer);
    size_t samples = length / sizeof(short);
    std::vector<float> pcmf32(samples);
    for (size_t i = 0; i < samples; i++) {
        pcmf32[i] = static_cast<float>(pcm_s16[i]) / 32768.0f;
    }
    env->ReleaseByteArrayElements(audio_data, buffer, 0);
    
    const char* modelPath = env->GetStringUTFChars(model_path, 0);
    const char* lang = env->GetStringUTFChars(language, 0);
    
    whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context* ctx = whisper_init_from_file_with_params(modelPath, cparams);
    env->ReleaseStringUTFChars(model_path, modelPath);
    
    if (!ctx) {
        env->ReleaseStringUTFChars(language, lang);
        return env->NewStringUTF("");
    }
    
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = lang;
    wparams.n_threads = 4;
    wparams.no_context = true;
    
    std::string text = "";
    if (whisper_full(ctx, wparams, pcmf32.data(), pcmf32.size()) == 0) {
        int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; ++i) {
            text += whisper_full_get_segment_text(ctx, i);
        }
    }
    
    whisper_free(ctx);
    env->ReleaseStringUTFChars(language, lang);
    
    // озвращаем готовую Java-строку через UTF-16
    return utf8ToJstring(env, text);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_MainActivity_transcribeAudio(
    JNIEnv* env, jobject, jstring audio_path, jstring model_path, jstring language) {
    
    const char* audioPath = env->GetStringUTFChars(audio_path, 0);
    const char* modelPath = env->GetStringUTFChars(model_path, 0);
    const char* lang = env->GetStringUTFChars(language, 0);
    
    whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context* ctx = whisper_init_from_file_with_params(modelPath, cparams);
    
    if (!ctx) {
        env->ReleaseStringUTFChars(audio_path, audioPath);
        env->ReleaseStringUTFChars(model_path, modelPath);
        env->ReleaseStringUTFChars(language, lang);
        return env->NewStringUTF("Error: Failed to load model");
    }
    
    std::ifstream ifs(audioPath, std::ios::binary);
    if (!ifs) {
        whisper_free(ctx);
        env->ReleaseStringUTFChars(audio_path, audioPath);
        env->ReleaseStringUTFChars(model_path, modelPath);
        env->ReleaseStringUTFChars(language, lang);
        return env->NewStringUTF("");
    }
    
    std::vector<short> pcm_s16;
    short sample;
    while (ifs.read(reinterpret_cast<char*>(&sample), sizeof(short))) {
        pcm_s16.push_back(sample);
    }
    ifs.close();
    
    if (pcm_s16.size() < 16000) {
        whisper_free(ctx);
        env->ReleaseStringUTFChars(audio_path, audioPath);
        env->ReleaseStringUTFChars(model_path, modelPath);
        env->ReleaseStringUTFChars(language, lang);
        return env->NewStringUTF("");
    }
    
    std::vector<float> pcmf32(pcm_s16.size());
    for (size_t i = 0; i < pcm_s16.size(); i++) {
        pcmf32[i] = static_cast<float>(pcm_s16[i]) / 32768.0f;
    }
    
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = lang;
    wparams.n_threads = 4;
    
    std::string text = "";
    if (whisper_full(ctx, wparams, pcmf32.data(), pcmf32.size()) == 0) {
        int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; ++i) {
            text += whisper_full_get_segment_text(ctx, i);
        }
    }
    
    whisper_free(ctx);
    env->ReleaseStringUTFChars(audio_path, audioPath);
    env->ReleaseStringUTFChars(model_path, modelPath);
    env->ReleaseStringUTFChars(language, lang);
    
    return utf8ToJstring(env, text);
}
