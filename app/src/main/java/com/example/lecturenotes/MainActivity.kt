package com.example.lecturenotes

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import com.example.lecturenotes.databinding.ActivityMainBinding
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import com.example.lecturenotes.transcription.WhisperTranscriber

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private var recordingJob: Job? = null
    private var liveText = ""
    private val audioChunks = mutableListOf<ByteArray>()
    
    // Обертка над Whisper.cpp (заменяет прямые JNI-вызовы)
    private lateinit var whisperTranscriber: WhisperTranscriber

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Запрос разрешений
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        // Инициализация WhisperTranscriber (модель грузится один раз)
        whisperTranscriber = WhisperTranscriber(this)
        val modelLoaded = whisperTranscriber.initialize()
        
        if (!modelLoaded) {
            Log.e("MainActivity", "Failed to initialize Whisper model")
            binding.tvResult.text = "Ошибка: модель не загружена"
        }

        binding.btnStart.setOnClickListener {
            if (!isRecording && whisperTranscriber.isReady()) {
                startRecording()
            }
        }

        binding.btnStop.setOnClickListener {
            if (isRecording) {
                stopRecording()
            }
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        audioChunks.clear()
        liveText = ""
        isRecording = true
        audioRecord?.startRecording()
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true

        recordingJob = CoroutineScope(Dispatchers.Default).launch {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    audioChunks.add(chunk)
                    
                    // Транскрибация чанка через WhisperTranscriber
                    val text = whisperTranscriber.processChunk(chunk, "ru")
                    if (text.isNotEmpty()) {
                        liveText += " $text"
                        withContext(Dispatchers.Main) {
                            binding.tvResult.text = liveText.trim()
                        }
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false

        // Сохраняем аудио в файл
        val audioFile = File(filesDir, "lecture_${System.currentTimeMillis()}.wav")
        saveAudioToFile(audioFile)

        // Финальная транскрибация (опционально)
        // val finalText = whisperTranscriber.processFile(audioFile.absolutePath, "ru")
        // binding.tvResult.text = finalText
    }

    private fun saveAudioToFile(file: File) {
        try {
            FileOutputStream(file).use { fos ->
                // Записываем WAV header
                val totalAudioLen = audioChunks.sumOf { it.size }.toLong()
                val totalDataLen = totalAudioLen + 36
                val sampleRateLong = sampleRate.toLong()
                val byteRate = (sampleRate * 2).toLong()
                val blockAlign = 2
                val bitsPerSample = 16

                val header = ByteArray(44)
                header[0] = 'R'.toByte(); header[1] = 'I'.toByte()
                header[2] = 'F'.toByte(); header[3] = 'F'.toByte()
                header[4] = (totalDataLen and 0xff).toByte()
                header[5] = (totalDataLen shr 8 and 0xff).toByte()
                header[6] = (totalDataLen shr 16 and 0xff).toByte()
                header[7] = (totalDataLen shr 24 and 0xff).toByte()
                header[8] = 'W'.toByte(); header[9] = 'A'.toByte()
                header[10] = 'V'.toByte(); header[11] = 'E'.toByte()
                header[12] = 'f'.toByte(); header[13] = 'm'.toByte()
                header[14] = 't'.toByte(); header[15] = ' '.toByte()
                header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
                header[20] = 1; header[21] = 0
                header[22] = 1; header[23] = 0
                header[24] = (sampleRateLong and 0xff).toByte()
                header[25] = (sampleRateLong shr 8 and 0xff).toByte()
                header[26] = (sampleRateLong shr 16 and 0xff).toByte()
                header[27] = (sampleRateLong shr 24 and 0xff).toByte()
                header[28] = (byteRate and 0xff).toByte()
                header[29] = (byteRate shr 8 and 0xff).toByte()
                header[30] = (byteRate shr 16 and 0xff).toByte()
                header[31] = (byteRate shr 24 and 0xff).toByte()
                header[32] = (blockAlign and 0xff).toByte()
                header[33] = (blockAlign shr 8 and 0xff).toByte()
                header[34] = (bitsPerSample and 0xff).toByte()
                header[35] = (bitsPerSample shr 8 and 0xff).toByte()
                header[36] = 'd'.toByte(); header[37] = 'a'.toByte()
                header[38] = 't'.toByte(); header[39] = 'a'.toByte()
                header[40] = (totalAudioLen and 0xff).toByte()
                header[41] = (totalAudioLen shr 8 and 0xff).toByte()
                header[42] = (totalAudioLen shr 16 and 0xff).toByte()
                header[43] = (totalAudioLen shr 24 and 0xff).toByte()

                fos.write(header)
                for (chunk in audioChunks) {
                    fos.write(chunk)
                }
            }
            Log.i("MainActivity", "Audio saved to: ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e("MainActivity", "Error saving audio", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Освобождаем модель при закрытии Activity
        whisperTranscriber.shutdown()
    }
}