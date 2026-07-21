package com.example.lecturenotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "START_RECORDING"
        const val ACTION_STOP = "STOP_RECORDING"
        const val RECORDING_FILE = "recording.wav"
        private const val CHANNEL_ID = "RecordingServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val TAG = "RecordingService"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Запись лекций",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление о записи аудио"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startRecording() {
        if (isRecording) return

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Failed to initialize AudioRecord")
            stopSelf()
            return
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        audioRecord?.startRecording()
        isRecording = true

        recordingJob = scope.launch {
            val audioFile = File(cacheDir, RECORDING_FILE)
            FileOutputStream(audioFile).use { outputStream ->
                writeWavHeader(outputStream)

                val buffer = ShortArray(bufferSize)
                var totalDataSize = 0L

                while (isRecording && isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val byteBuffer = ByteArray(read * 2)
                        for (i in 0 until read) {
                            val value = buffer[i]
                            byteBuffer[i * 2] = (value.toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
                        }
                        outputStream.write(byteBuffer)
                        totalDataSize += byteBuffer.size

                        AudioBuffer.addChunk(byteBuffer)
                    }
                }

                updateWavHeader(outputStream, totalDataSize)
            }
        }

        Log.i(TAG, "Recording started")
    }

    private fun writeWavHeader(outputStream: FileOutputStream) {
        val header = ByteArray(44)
        // RIFF chunk
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        // Placeholder for file size
        header[4] = 0
        header[5] = 0
        header[6] = 0
        header[7] = 0
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = 1
        header[23] = 0
        // Sample rate
        header[24] = (SAMPLE_RATE and 0xFF).toByte()
        header[25] = ((SAMPLE_RATE shr 8) and 0xFF).toByte()
        header[26] = ((SAMPLE_RATE shr 16) and 0xFF).toByte()
        header[27] = ((SAMPLE_RATE shr 24) and 0xFF).toByte()
        // Byte rate
        val byteRate = SAMPLE_RATE * 2
        header[28] = (byteRate and 0xFF).toByte()
        header[29] = ((byteRate shr 8) and 0xFF).toByte()
        header[30] = ((byteRate shr 16) and 0xFF).toByte()
        header[31] = ((byteRate shr 24) and 0xFF).toByte()
        header[32] = 2
        header[33] = 0
        header[34] = 16
        header[35] = 0
        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        // Placeholder for data size
        header[40] = 0
        header[41] = 0
        header[42] = 0
        header[43] = 0

        outputStream.write(header)
    }

    private fun updateWavHeader(outputStream: FileOutputStream, dataSize: Long) {
        val channel = outputStream.channel
        val totalSize = dataSize + 36

        // Update RIFF chunk size
        channel.position(4)
        val fileSize = ByteArray(4)
        fileSize[0] = (totalSize and 0xFF).toByte()
        fileSize[1] = ((totalSize shr 8) and 0xFF).toByte()
        fileSize[2] = ((totalSize shr 16) and 0xFF).toByte()
        fileSize[3] = ((totalSize shr 24) and 0xFF).toByte()
        channel.write(java.nio.ByteBuffer.wrap(fileSize))

        // Update data chunk size
        channel.position(40)
        val data = ByteArray(4)
        data[0] = (dataSize and 0xFF).toByte()
        data[1] = ((dataSize shr 8) and 0xFF).toByte()
        data[2] = ((dataSize shr 16) and 0xFF).toByte()
        data[3] = ((dataSize shr 24) and 0xFF).toByte()
        channel.write(java.nio.ByteBuffer.wrap(data))
    }

    private fun stopRecording() {
        if (!isRecording) return

        isRecording = false
        recordingJob?.cancel()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        stopForeground(true)
        stopSelf()

        Log.i(TAG, "Recording stopped")
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Запись лекции")
            .setContentText("Идет запись аудио...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopRecording()
        scope.cancel()
        super.onDestroy()
    }
}