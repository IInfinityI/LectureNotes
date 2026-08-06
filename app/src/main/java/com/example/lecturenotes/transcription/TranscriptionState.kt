package com.example.lecturenotes.transcription

/**
 * UI-состояние экрана стриминговой транскрибации.
 * Immutable data class — копируется через copy() при каждом изменении.
 */
data class TranscriptionState(
    val isRecording: Boolean = false,
    val isFinalizing: Boolean = false,
    val liveText: String = "",
    val finalizedText: String = "",
    val wordCount: Int = 0,
    val error: String? = null
) {
    val fullText: String
        get() = (finalizedText + " " + liveText).trim()
}