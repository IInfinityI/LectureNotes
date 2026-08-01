package com.example.lecturenotes.transcription

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class WhisperTranscriber(private val context: Context) {
    
    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val MODEL_FILE_NAME = "ggml-tiny.bin"
        
        // Загрузка нативной библиотеки
        init {
            System.loadLibrary("whisper_jni_bridge")
        }
    }
    
    // JNI методы (реализованы в whisper_jni_bridge.cpp)
    private external fun initModel(modelPath: String): Boolean
    private external fun releaseModel()
    private external fun transcribeChunk(audioData: ByteArray, language: String): String
    private external fun transcribeAudio(audioPath: String, language: String): String
    
    private var isInitialized = false
    private var isModelReleased = false
    
    /**
     * Инициализация модели Whisper
     * Копирует модель из assets в cacheDir и загружает её
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            Log.i(TAG, "Model already initialized")
            return true
        }
        
        try {
            // Копируем модель из assets в cacheDir
            val modelFile = copyModelFromAssets()
            if (modelFile == null) {
                Log.e(TAG, "Failed to copy model from assets")
                return false
            }
            
            // Инициализируем модель через JNI
            val result = initModel(modelFile.absolutePath)
            isInitialized = result
            
            if (result) {
                Log.i(TAG, "Model initialized successfully from: ${modelFile.absolutePath}")
            } else {
                Log.e(TAG, "Failed to initialize model")
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing model: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Проверка готовности модели к работе
     */
    fun isReady(): Boolean {
        return isInitialized && !isModelReleased
    }
    
    /**
     * Транскрибация аудио-чанка в реальном времени
     * @param audioData PCM 16-bit, 16kHz, mono
     * @param language Код языка (например, "ru", "en")
     * @return Распознанный текст
     */
    suspend fun processChunk(audioData: ByteArray, language: String): String {
        if (!isReady()) {
            Log.w(TAG, "Model not ready, cannot process chunk")
            return ""
        }
        
        return withContext(Dispatchers.Default) {
            try {
                val result = transcribeChunk(audioData, language)
                Log.d(TAG, "Chunk transcribed: ${result.length} chars")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error transcribing chunk: ${e.message}", e)
                ""
            }
        }
    }
    
    /**
     * Транскрибация полного аудиофайла
     * @param audioPath Путь к файлу PCM 16-bit, 16kHz, mono
     * @param language Код языка (например, "ru", "en")
     * @return Распознанный текст
     */
    suspend fun processFile(audioPath: String, language: String): String {
        if (!isReady()) {
            Log.w(TAG, "Model not ready, cannot process file")
            return ""
        }
        
        return withContext(Dispatchers.Default) {
            try {
                val result = transcribeAudio(audioPath, language)
                Log.d(TAG, "File transcribed: ${result.length} chars")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error transcribing file: ${e.message}", e)
                ""
            }
        }
    }
    
    /**
     * Освобождение ресурсов модели
     */
    fun shutdown() {
        if (isInitialized && !isModelReleased) {
            releaseModel()
            isModelReleased = true
            isInitialized = false
            Log.i(TAG, "Model released")
        }
    }
    
    /**
     * Копирование модели из assets в cacheDir
     */
    private fun copyModelFromAssets(): File? {
        return try {
            val modelDir = File(context.cacheDir, "models")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }
            
            val modelFile = File(modelDir, MODEL_FILE_NAME)
            
            // Если модель уже скопирована - возвращаем её
            if (modelFile.exists()) {
                Log.i(TAG, "Model already exists in cache: ${modelFile.absolutePath}")
                return modelFile
            }
            
            // Копируем из assets
            context.assets.open(MODEL_FILE_NAME).use { inputStream ->
                FileOutputStream(modelFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            Log.i(TAG, "Model copied to cache: ${modelFile.absolutePath}")
            modelFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying model from assets: ${e.message}", e)
            null
        }
    }
    
    // Финализация - освобождаем ресурсы при сборке мусора
    protected fun finalize() {
        shutdown()
    }
}