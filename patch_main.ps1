$path = "app\src\main\java\com\example\lecturenotes\MainActivity.kt"
$c = Get-Content $path -Raw -Encoding UTF8

$c = $c.Replace(
    'external fun transcribeAudio(audioPath: String, modelPath: String, language: String): String',
    "external fun transcribeAudio(audioPath: String, modelPath: String, language: String): String`n    external fun transcribeChunk(audioData: ByteArray, modelPath: String, language: String): String"
)

$c = $c.Replace(
    'import kotlinx.coroutines.launch',
    "import kotlinx.coroutines.launch`nimport kotlinx.coroutines.Job`nimport kotlinx.coroutines.isActive"
)

$c = $c.Replace(
    'var editingRecording by remember { mutableStateOf<Recording?>(null) }',
    "var editingRecording by remember { mutableStateOf<Recording?>(null) }`n        var streamingJob by remember { mutableStateOf<Job?>(null) }"
)

$old_logic = @"
                            if (isRecording) {
                                stopRecording(context)
                                isRecording = false
                                isProcessing = true
                                transcriptionResult = "бработка аудио..."
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        delay(500)
                                        val audioFile = cacheDir.listFiles { f -> f.name.startsWith("recording_") && f.name.endsWith(".pcm") }
                                            ?.maxByOrNull { it.lastModified() }
                                        if (audioFile != null) {
                                            val modelFile = File(cacheDir, "ggml-tiny.bin")
                                            transcribeAudio(audioFile.absolutePath, modelFile.absolutePath, language)
                                        } else {
                                            "айл записи не найден"
                                        }
                                    }
                                    transcriptionResult = result
                                    isProcessing = false
                                    val isRealText = result.isNotEmpty() && result != "айл записи не найден" && !result.startsWith("Error:")
                                    if (isRealText) { viewModel.addRecording(result) }
                                }
                            } else {
                                startRecording(context)
                                isRecording = true
                                transcriptionResult = "апись..."
                            }
"@

$new_logic = @"
                            if (isRecording) {
                                streamingJob?.cancel()
                                stopRecording(context)
                                isRecording = false
                                isProcessing = true
                                transcriptionResult = "инализация..."
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        delay(500)
                                        val audioFile = File(cacheDir, "recording_live.pcm")
                                        if (audioFile.exists()) {
                                            val modelFile = File(cacheDir, "ggml-tiny.bin")
                                            transcribeAudio(audioFile.absolutePath, modelFile.absolutePath, language)
                                        } else {
                                            transcriptionResult
                                        }
                                    }
                                    transcriptionResult = result
                                    isProcessing = false
                                    val isRealText = result.isNotEmpty() && !result.startsWith("Error:")
                                    if (isRealText) { viewModel.addRecording(result) }
                                }
                            } else {
                                startRecording(context)
                                isRecording = true
                                transcriptionResult = "Слушаю..."
                                AudioBuffer.clear()
                                streamingJob = scope.launch {
                                    val modelFile = File(cacheDir, "ggml-tiny.bin")
                                    while (isActive) {
                                        delay(3000)
                                        val audioData = AudioBuffer.getAllData()
                                        if (audioData.size > 32000) {
                                            val chunkResult = withContext(Dispatchers.IO) {
                                                transcribeChunk(audioData, modelFile.absolutePath, language)
                                            }
                                            if (chunkResult.isNotEmpty()) {
                                                transcriptionResult = chunkResult
                                            }
                                        }
                                    }
                                }
                            }
"@

if ($c.Contains($old_logic)) {
    $c = $c.Replace($old_logic, $new_logic)
    Write-Host "Logic replaced successfully."
} else {
    Write-Host "WARNING: Could not find old logic block!"
}

Set-Content $path -Value $c -Encoding UTF8
