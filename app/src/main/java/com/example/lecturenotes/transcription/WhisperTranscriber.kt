package com.example.lecturenotes.transcription

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Обёртка над Whisper.cpp JNI.
 * Управляет жизненным циклом моделей: загрузка, переключение, освобождение.
 * Thread-safe: все операции защищены Mutex.
 * 
 * ДВУХПОТОЧНАЯ АРХИТЕКТУРА (Приоритет 3):
 * - STREAMING_MODEL (tiny): быстрая модель для live-транскрибации
 * - FINAL_MODEL (base-q5_0): точная модель для финальной транскрибации
 * - Модели загружаются независимо в пуле (native-слой)
 */
class WhisperTranscriber(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "WhisperTranscriber"
        
        // Модели для двухпоточной архитектуры
        private const val STREAMING_MODEL = "ggml-tiny.bin"
        private const val FINAL_MODEL = "ggml-base-q5_0.bin"
        
        private const val MIN_CHUNK_BYTES = 32000 // 1 сек: 16000 samples * 2 bytes

        init {
            System.loadLibrary("whisper_jni_bridge")
        }
    }

    // Состояние моделей (путь на диске, не имя файла)
    @Volatile
    private var streamingModelPath: String? = null
    
    @Volatile
    private var finalModelPath: String? = null

    private val mutex = Mutex()

    // --- НОВЫЙ JNI API (с явным выбором модели) ---
    private external fun nativeInitModel(modelPath: String): Boolean
    private external fun nativeIsModelLoaded(modelPath: String): Boolean
    private external fun nativeTranscribeChunkWithModel(
        audioData: ByteArray, language: String, modelPath: String
    ): String
    private external fun nativeTranscribeAudioWithModel(
        audioPath: String, language: String, modelPath: String
    ): String
    private external fun nativeReleaseModelByPath(modelPath: String)
    private external fun nativeReleaseAllModels()

    // --- LEGACY JNI API (для обратной совместимости) ---
    private external fun initModel(modelPath: String): Boolean
    private external fun releaseModel()
    private external fun transcribeChunk(audioData: ByteArray, language: String): String
    private external fun transcribeAudio(audioPath: String, language: String): String

    /**
     * Инициализация streaming-модели (tiny).
     * НЕ блокирует main thread.
     * @param modelName Имя файла модели в assets (по умолчанию STREAMING_MODEL)
     * @return true если модель загружена
     */
    suspend fun initialize(modelName: String = STREAMING_MODEL): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val modelPath = getModelPath(modelName)
            if (modelPath == null) {
                Log.e(TAG, "Model file '$modelName' not found in assets or copy failed")
                return@withContext false
            }

            // Проверяем, уже ли загружена эта модель
            if (nativeIsModelLoaded(modelPath)) {
                streamingModelPath = modelPath
                Log.i(TAG, "Streaming model '$modelName' already loaded")
                return@withContext true
            }

            val success = nativeInitModel(modelPath)
            if (success) {
                streamingModelPath = modelPath
                Log.i(TAG, "Streaming model '$modelName' loaded from: $modelPath")
            } else {
                Log.e(TAG, "Failed to load streaming model '$modelName' from path: $modelPath")
            }

            success
        }
    }

    /**
     * Инициализация финальной модели (base-q5_0).
     * Вызывается лениво при первой попытке processFile().
     */
    private suspend fun ensureFinalModelLoaded(): Boolean = mutex.withLock {
        val currentPath = finalModelPath
        if (currentPath != null && nativeIsModelLoaded(currentPath)) {
            return@withLock true
        }

        val modelPath = getModelPath(FINAL_MODEL)
        if (modelPath == null) {
            Log.e(TAG, "Final model '$FINAL_MODEL' not found")
            return@withLock false
        }

        val success = nativeInitModel(modelPath)
        if (success) {
            finalModelPath = modelPath
            Log.i(TAG, "Final model '$FINAL_MODEL' loaded from: $modelPath")
        } else {
            Log.e(TAG, "Failed to load final model '$FINAL_MODEL'")
        }

        success
    }

    /**
     * Транскрибация чанка (использует streaming-модель tiny).
     * @param audioData PCM 16-bit mono, 16kHz
     * @param language Код языка ("ru", "en", "auto")
     * @return Распознанный текст или пустая строка
     */
    suspend fun processChunk(audioData: ByteArray, language: String = "ru"): String = withContext(Dispatchers.IO) {
        val path = streamingModelPath
        if (path == null) {
            Log.e(TAG, "Streaming model not initialized. Cannot process chunk.")
            return@withContext ""
        }

        if (!nativeIsModelLoaded(path)) {
            Log.e(TAG, "Streaming model not loaded. Call initialize() first.")
            return@withContext ""
        }

        if (audioData.isEmpty() || audioData.size < MIN_CHUNK_BYTES) {
            Log.d(TAG, "Chunk too small (${audioData.size} bytes), skipping.")
            return@withContext ""
        }

        try {
            val result = nativeTranscribeChunkWithModel(audioData, language, path)
            Log.d(TAG, "Chunk transcribed: ${result.take(50)}...")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error transcribing chunk", e)
            ""
        }
    }

    /**
     * Транскрибация полного файла (использует финальную модель base-q5_0).
     * Автоматически загружает финальную модель, если она не загружена.
     * @param audioPath Путь к raw PCM файлу (16-bit, 16kHz, mono)
     * @param language Код языка
     * @return Распознанный текст
     */
    suspend fun processFile(audioPath: String, language: String = "ru"): String = withContext(Dispatchers.IO) {
        if (!ensureFinalModelLoaded()) {
            Log.e(TAG, "Final model not available for processFile()")
            return@withContext ""
        }

        val path = finalModelPath!!
        val file = File(audioPath)
        if (!file.exists()) {
            Log.e(TAG, "Audio file not found: $audioPath")
            return@withContext ""
        }

        if (file.length() < MIN_CHUNK_BYTES) {
            Log.e(TAG, "Audio file too small: ${file.length()} bytes")
            return@withContext ""
        }

        try {
            nativeTranscribeAudioWithModel(audioPath, language, path)
        } catch (e: Exception) {
            Log.e(TAG, "Error transcribing file", e)
            ""
        }
    }

    /**
     * Освобождение всех моделей. Безопасно вызывать многократно.
     */
    fun shutdown() {
        nativeReleaseAllModels()
        streamingModelPath = null
        finalModelPath = null
        Log.i(TAG, "All models released")
    }

    override fun close() = shutdown()

    /**
     * Проверка готовности streaming-модели.
     */
    fun isReady(): Boolean {
        val path = streamingModelPath
        return path != null && nativeIsModelLoaded(path)
    }

    /**
     * Копирует модель из assets во внутреннее хранилище.
     * Возвращает абсолютный путь или null.
     */
    private fun getModelPath(modelName: String): String? {
        val internalFile = File(context.filesDir, modelName)

        // Проверяем, есть ли уже скопированный файл и он не пустой (т.е. не Git LFS pointer)
        if (internalFile.exists() && internalFile.length() > 1024) {
            Log.i(TAG, "Using existing model from filesDir: $modelName (${internalFile.length()} bytes)")
            return internalFile.absolutePath
        }

        // Копируем из assets
        return try {
            context.assets.open(modelName).use { input ->
                FileOutputStream(internalFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Model copied from assets: $modelName (${internalFile.length()} bytes)")
            // Проверяем размер файла после копирования. Если меньше 10MB, это точно заглушка.
            if (internalFile.length() < (10 * 1024 * 1024)) {
                Log.e(TAG, "Copied model file is suspiciously small (<10MB). Check if it's a real model: ${internalFile.length()}")
                internalFile.delete()
                return null
            }
            internalFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model '$modelName' from assets", e)
            if (internalFile.exists()) internalFile.delete()
            null
        }
    }
}