package com.example.lecturenotes.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton Repository для обмена аудио-чанками между RecordingService и ViewModel.
 * Использует SharedFlow для публикации чанков и StateFlow для ошибок.
 */
object AudioChunkRepository {
    
    // SharedFlow для аудио-чанков (replay = 0, extraBufferCapacity = 64)
    private val _audioChunks = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val audioChunks: SharedFlow<ByteArray> = _audioChunks.asSharedFlow()

    // StateFlow для ошибок от сервиса
    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    /**
     * Публикация аудио-чанка.
     * @param chunk PCM 16-bit mono, 16kHz
     */
    suspend fun emitChunk(chunk: ByteArray) {
        _audioChunks.emit(chunk)
    }

    /**
     * Публикация ошибки от сервиса.
     * @param message Сообщение об ошибке
     */
    fun emitError(message: String) {
        _errors.value = message
    }

    /**
     * Очистка ошибки (вызывается UI после отображения).
     */
    fun clearError() {
        _errors.value = null
    }
}