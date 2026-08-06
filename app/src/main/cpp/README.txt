# Whisper C++ Backend

## Overview
This directory contains the native C++ implementation for Whisper-based audio transcription, interfacing with the Kotlin frontend via JNI.

## Key Components
- `native-lib.cpp`: Main JNI wrapper and Whisper integration.
- `CMakeLists.txt`: Build configuration for native libraries.
- `build.gradle` (app): Includes native build settings.

## Streaming Transcription Fix
- **Issue:** Model was reloading every 3 seconds, causing stuttering.
- **Root Cause:** `g_wparams.no_context = true` was set, which discards all context between chunks.
- **Fix:** 
  - Changed `no_context` to `false`.
  - Introduced a sliding `g_prompt_buffer` to maintain context between audio chunks.
  - Added logic to update the prompt with the last transcribed text, allowing the model to continue from where it left off.
- **Result:** Continuous, context-aware streaming transcription without reloading the model. Significant performance improvement.

## Performance Notes
- Model is now initialized once per session (global `g_ctx`).
- Audio chunks are processed in a background thread (`audio_worker`).
- Context persistence minimizes re-computation and improves fluency.

## JNI Interface
- `initModel`: Loads Whisper model into `g_ctx`.
- `loadAudioChunk`: Adds audio data to processing queue.
- `startProcessing`: Starts the background worker thread.
- `stopProcessing`: Signals worker to stop.
- `getCurrentText`: Returns accumulated transcription.
- `releaseModel`: Frees `g_ctx` and resets buffers.

## Dependencies
- [ggerganov/whisper.cpp](https://github.com/ggerganov/whisper.cpp)
- Android NDK r23+
