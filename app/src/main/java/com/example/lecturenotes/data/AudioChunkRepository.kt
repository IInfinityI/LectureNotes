package com.example.lecturenotes.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Singleton-репозиторий для передачи аудио-чанков между RecordingService и ViewModel.
 * Используется для обеспечения доступа к одному и тому же Flow из разных компонентов.
 */
object AudioChunkRepository {
    private val _audioChunks = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 10
    )

    val audioChunks: SharedFlow<ByteArray> = _audioChunks.asSharedFlow()

    /**
     * Метод для эмита чанка из RecordingService.
     */
    fun emitChunk(chunk: ByteArray) {
        _audioChunks.tryEmit(chunk)
    }
}