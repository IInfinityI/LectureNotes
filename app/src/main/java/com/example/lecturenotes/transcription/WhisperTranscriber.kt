package com.example.lecturenotes.transcription

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Обертка над Whisper.cpp JNI.
 * Управляет жизненным циклом модели: загрузка один раз, освобождение при закрытии.
 */
class WhisperTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val MODEL_FILE_NAME = "ggml-base.bin"

        // Загружаем нативную библиотеку один раз
        init {
            System.loadLibrary("lecturenotes")
        }
    }

    private var isModelInitialized = false

    // Нативные методы (JNI) - привязаны к этому классу
    private external fun initModel(modelPath: String): Boolean
    private external fun releaseModel()
    private external fun transcribeChunk(audioData: ByteArray, language: String): String
    private external fun transcribeAudio(audioPath: String, language: String): String

    /**
     * Инициализация модели Whisper.
     * Копирует модель из assets в внутреннее хранилище (если нужно) и загружает в память.
     * Вызывается ОДИН раз при старте приложения.
     */
    fun initialize(): Boolean {
        if (isModelInitialized) {
            Log.i(TAG, "Model already initialized")
            return true
        }

        val modelPath = getModelPath()
        if (modelPath == null) {
            Log.e(TAG, "Model file not found")
            return false
        }

        val success = initModel(modelPath)
        isModelInitialized = success

        if (success) {
            Log.i(TAG, "Whisper model loaded successfully from: $modelPath")
        } else {
            Log.e(TAG, "Failed to load Whisper model")
        }

        return success
    }

    /**
     * Освобождение модели из памяти.
     * Вызывается при закрытии приложения или когда модель больше не нужна.
     */
    fun shutdown() {
        if (isModelInitialized) {
            releaseModel()
            isModelInitialized = false
            Log.i(TAG, "Whisper model released")
        }
    }

    /**
     * Транскрибация аудио-чанка в реальном времени.
     * @param audioData PCM 16-bit mono, 16kHz
     * @param language Код языка ("ru", "en", "auto")
     * @return Распознанный текст или пустая строка
     */
    fun processChunk(audioData: ByteArray, language: String = "ru"): String {
        if (!isModelInitialized) {
            Log.e(TAG, "Model not initialized! Call initialize() first")
            return ""
        }

        if (audioData.size < 32000) { // Минимум 1 секунда аудио
            return ""
        }

        return try {
            transcribeChunk(audioData, language)
        } catch (e: Exception) {
            Log.e(TAG, "Error transcribing chunk", e)
            ""
        }
    }

    /**
     * Транскрибация полного аудиофайла.
     * @param audioPath Путь к WAV файлу
     * @param language Код языка ("ru", "en", "auto")
     * @return Распознанный текст
     */
    fun processFile(audioPath: String, language: String = "ru"): String {
        if (!isModelInitialized) {
            Log.e(TAG, "Model not initialized! Call initialize() first")
            return ""
        }

        return try {
            transcribeAudio(audioPath, language)
        } catch (e: Exception) {
            Log.e(TAG, "Error transcribing file", e)
            ""
        }
    }

    /**
     * Получение пути к модели.
     * Сначала проверяет внутреннее хранилище, потом assets.
     */
    private fun getModelPath(): String? {
        val internalModelFile = File(context.filesDir, MODEL_FILE_NAME)
        
        // Если модель уже скопирована во внутреннее хранилище
        if (internalModelFile.exists()) {
            return internalModelFile.absolutePath
        }

        // Пытаемся скопировать из assets
        return try {
            context.assets.open(MODEL_FILE_NAME).use { input ->
                FileOutputStream(internalModelFile).use { output ->
                    input.copyTo(output)
                }
            }
            internalModelFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model from assets", e)
            null
        }
    }

    /**
     * Проверка, инициализирована ли модель.
     */
    fun isReady(): Boolean = isModelInitialized
}