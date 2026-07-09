#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <android/log.h>
#include "whisper.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LectureNotes", __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_lecturenotes_MainActivity_stringFromJNI(JNIEnv* env, jobject) {
    return env->NewStringUTF("Whisper.cpp loaded");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_lecturenotes_MainActivity_transcribeChunk(
    JNIEnv* env, jobject, jbyteArray audio_data, jstring model_path, jstring language) {
    
    jsize length = env->GetArrayLength(audio_data);
    if (length < 32000) {
        jbyteArray empty = env->NewByteArray(0);
        return empty;
    }
    
    jbyte* buffer = env->GetByteArrayElements(audio_data, 0);
    short* pcm_s16 = reinterpret_cast<short*>(buffer);
    size_t samples = length / sizeof(short);
    std::vector<float> pcmf32(samples);
    for (size_t i = 0; i < samples; i++) {
        pcmf32[i] = static_cast<float>(pcm_s16[i]) / 32768.0f;
    }
    env->ReleaseByteArrayElements(audio_data, buffer, 0);
    
    const char *modelPath = env->GetStringUTFChars(model_path, 0);
    const char *lang = env->GetStringUTFChars(language, 0);
    
    whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context * ctx = whisper_init_from_file_with_params(modelPath, cparams);
    env->ReleaseStringUTFChars(model_path, modelPath);
    
    if (!ctx) {
        env->ReleaseStringUTFChars(language, lang);
        jbyteArray empty = env->NewByteArray(0);
        return empty;
    }
    
    // спользуем BEAM SEARCH для лучшего качества (вместо greedy)
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = lang;
    wparams.n_threads = 4;
    wparams.no_context = true;
    wparams.beam_search.beam_size = 5;  // ольше = лучше качество, но медленнее
    wparams.beam_search.patience = -1.0f;
    
    std::string text = "";
    if (whisper_full(ctx, wparams, pcmf32.data(), pcmf32.size()) == 0) {
        const int n_segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < n_segments; ++i) {
            text += whisper_full_get_segment_text(ctx, i);
        }
    }
    
    whisper_free(ctx);
    env->ReleaseStringUTFChars(language, lang);
    
    jbyteArray result = env->NewByteArray(text.size());
    env->SetByteArrayRegion(result, 0, text.size(), reinterpret_cast<const jbyte*>(text.c_str()));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_lecturenotes_MainActivity_transcribeAudio(
    JNIEnv* env, jobject, jstring audio_path, jstring model_path, jstring language) {
    
    const char *audioPath = env->GetStringUTFChars(audio_path, 0);
    const char *modelPath = env->GetStringUTFChars(model_path, 0);
    const char *lang = env->GetStringUTFChars(language, 0);
    
    whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context * ctx = whisper_init_from_file_with_params(modelPath, cparams);
    
    if (!ctx) {
        env->ReleaseStringUTFChars(audio_path, audioPath);
        env->ReleaseStringUTFChars(model_path, modelPath);
        env->ReleaseStringUTFChars(language, lang);
        std::string err = "Error: Failed to load model";
        jbyteArray result = env->NewByteArray(err.size());
        env->SetByteArrayRegion(result, 0, err.size(), reinterpret_cast<const jbyte*>(err.c_str()));
        return result;
    }
    
    std::ifstream ifs(audioPath, std::ios::binary);
    if (!ifs) {
        whisper_free(ctx);
        env->ReleaseStringUTFChars(audio_path, audioPath);
        env->ReleaseStringUTFChars(model_path, modelPath);
        env->ReleaseStringUTFChars(language, lang);
        jbyteArray empty = env->NewByteArray(0);
        return empty;
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
        jbyteArray empty = env->NewByteArray(0);
        return empty;
    }
    
    std::vector<float> pcmf32(pcm_s16.size());
    for (size_t i = 0; i < pcm_s16.size(); i++) {
        pcmf32[i] = static_cast<float>(pcm_s16[i]) / 32768.0f;
    }
    
    // BEAM SEARCH для финальной транскрибации (максимальное качество)
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = lang;
    wparams.n_threads = 4;
    wparams.beam_search.beam_size = 5;
    wparams.beam_search.patience = -1.0f;
    
    std::string text = "";
    if (whisper_full(ctx, wparams, pcmf32.data(), pcmf32.size()) == 0) {
        const int n_segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < n_segments; ++i) {
            text += whisper_full_get_segment_text(ctx, i);
        }
    }
    
    whisper_free(ctx);
    env->ReleaseStringUTFChars(audio_path, audioPath);
    env->ReleaseStringUTFChars(model_path, modelPath);
    env->ReleaseStringUTFChars(language, lang);
    
    jbyteArray result = env->NewByteArray(text.size());
    env->SetByteArrayRegion(result, 0, text.size(), reinterpret_cast<const jbyte*>(text.c_str()));
    return result;
}
