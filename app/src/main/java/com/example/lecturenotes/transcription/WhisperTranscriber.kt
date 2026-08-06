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
 * Управляет жизненным циклом модели: загрузка, переключение, освобождение.
 * Thread-safe: все операции защищены Mutex.
 */
class WhisperTranscriber(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "WhisperTranscriber"
        // ИСПРАВЛЕНО: имя файла модели соответствует реальному в assets/
        private const val DEFAULT_MODEL = "ggml-base-q5_0.bin"
        private const val MIN_CHUNK_BYTES = 32000 // 1 сек: 16000 samples * 2 bytes

        init {
            System.loadLibrary("whisper_jni_bridge")
        }
    }

    @Volatile
    private var isModelInitialized = false

    private var currentModelName: String? = null

    private val mutex = Mutex()

    // JNI-методы
    private external fun initModel(modelPath: String): Boolean
    private external fun releaseModel()
    private external fun transcribeChunk(audioData: ByteArray, language: String): String
    private external fun transcribeAudio(audioPath: String, language: String): String

    /**
     * Инициализация модели. НЕ блокирует main thread.
     * @param modelName Имя файла модели в assets
     * @return true если модель загружена
     */
    suspend fun initialize(modelName: String = DEFAULT_MODEL): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isModelInitialized && currentModelName == modelName) {
                Log.i(TAG, "Model '$modelName' already initialized")
                return@withContext true
            }

            if (isModelInitialized) {
                releaseModel()
                isModelInitialized = false
                currentModelName = null
                Log.i(TAG, "Previous model released for switch to '$modelName'")
            }

            val modelPath = getModelPath(modelName)
            if (modelPath == null) {
                Log.e(TAG, "Model file '$modelName' not found in assets or copy failed")
                return@withContext false
            }

            val success = initModel(modelPath)
            isModelInitialized = success

            if (success) {
                currentModelName = modelName
                Log.i(TAG, "Model '$modelName' loaded from: $modelPath")
            } else {
                Log.e(TAG, "Failed to load model '$modelName' from path: $modelPath")
            }

            success
        }
    }

    /**
     * Транскрибация чанка.
     * @param audioData PCM 16-bit mono, 16kHz
     * @param language Код языка ("ru", "en", "auto")
     * @return Распознанный текст или пустая строка
     */
    suspend fun processChunk(audioData: ByteArray, language: String = "ru"): String = withContext(Dispatchers.IO) {
        if (!isModelInitialized) {
            Log.e(TAG, "Model not initialized. Cannot process chunk.")
            return@withContext ""
        }

        if (audioData.isEmpty() || audioData.size < MIN_CHUNK_BYTES) {
            Log.d(TAG, "Chunk too small (${audioData.size} bytes), skipping.")
            return@withContext ""
        }

        try {
            val result = transcribeChunk(audioData, language)
            Log.d(TAG, "Chunk transcribed: ${result.take(50)}...") // Логируем начало результата
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error transcribing chunk", e)
            ""
        }
    }

    /**
     * Транскрибация полного файла.
     * @param audioPath Путь к raw PCM файлу (16-bit, 16kHz, mono)
     * @param language Код языка
     * @return Распознанный текст
     */
    suspend fun processFile(audioPath: String, language: String = "ru"): String = withContext(Dispatchers.IO) {
        if (!isModelInitialized) {
            Log.e(TAG, "Model not initialized")
            return@withContext ""
        }

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
            transcribeAudio(audioPath, language)
        } catch (e: Exception) {
            Log.e(TAG, "Error transcribing file", e)
            ""
        }
    }

    /**
     * Освобождение модели. Безопасно вызывать многократно.
     */
    fun shutdown() {
        if (isModelInitialized) {
            releaseModel()
            isModelInitialized = false
            currentModelName = null
            Log.i(TAG, "Model released")
        }
    }

    override fun close() = shutdown()

    fun isReady(): Boolean = isModelInitialized

    /**
     * Копирует модель из assets во внутреннее хранилище.
     * Возвращает абсолютный путь или null.
     */
    private fun getModelPath(modelName: String): String? {
        val internalFile = File(context.filesDir, modelName)

        // Проверяем, есть ли уже скопированный файл и он не пустой (т.е. не Git LFS pointer)
        if (internalFile.exists() && internalFile.length() > 1024) { // Порог в 1KB для уверенности
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
            if (internalFile.length() < (10 * 1024 * 1024)) { // 10MB threshold
                Log.e(TAG, "Copied model file is suspiciously small (<10MB). Check if it's a real model: ${internalFile.length()}")
                internalFile.delete() // Удаляем заглушку
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