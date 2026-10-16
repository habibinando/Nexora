package com.example.neural

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class TrainingStatus {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
    STOPPED
}

data class TrainingTelemetry(
    val status: TrainingStatus = TrainingStatus.IDLE,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val currentEpoch: Int = 0,
    val totalEpochs: Int = 0,
    val latestLoss: Float? = null,
    val initialLoss: Float? = null,
    val lossHistory: List<Float> = emptyList(),
    val stepDurationMs: Float? = null,
    val gflops: Float? = null,
    val gradientNorm: Float? = null,
    val isNdkAccelerated: Boolean = false,
    val totalParameters: Long? = null,
    val hiddenSize: Int = 64,
    val vocabSize: Int = 0,
    val statusMessage: String = "Siap memulai pelatihan on-device"
)

data class ChatMessage(
    val id: String,
    val sender: ChatSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tokensGenerated: Int = 0,
    val latencyMs: Long = 0,
    val tokensPerSec: Float = 0f,
    val isNdkUsed: Boolean = false
)

enum class ChatSender {
    USER,
    NEXORA,
    SYSTEM
}

class LocalNeuralTrainer(
    private val scope: CoroutineScope
) {
    private val _telemetry = MutableStateFlow(TrainingTelemetry())
    val telemetry: StateFlow<TrainingTelemetry> = _telemetry.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                id = "init_welcome",
                sender = ChatSender.NEXORA,
                text = "Halo! Saya Nexora, model AI saraf on-device Anda. Silakan latih bobot saya di tab Training, atau langsung ajukan pertanyaan untuk menguji respons generasi karakter secara real-time.",
                tokensGenerated = 0,
                latencyMs = 0,
                tokensPerSec = 0f,
                isNdkUsed = NdkNeuralAccelerator.isNdkAvailable
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isChatGenerating = MutableStateFlow(false)
    val isChatGenerating: StateFlow<Boolean> = _isChatGenerating.asStateFlow()

    private var trainingJob: Job? = null
    var activeModel: CharRNNModel? = null
        private set

    private var trainingData: String = ""
    private var seqLength: Int = 32
    private var learningRate: Float = 0.01f
    private var targetEpochs: Int = 5

    init {
        // Automatically initialize model with default dataset
        val defaultDataset = DatasetRepository.activeDataset.value
        createCustomModel(hiddenSize = 64, datasetText = defaultDataset.text)
    }

    /**
     * Builds and creates a custom model architecture with specified hidden size and dataset vocabulary.
     */
    fun createCustomModel(hiddenSize: Int = 64, datasetText: String = DatasetRepository.activeDataset.value.text, seed: Long = 42L): CharRNNModel {
        val cleanText = datasetText.ifBlank { "Nexora On-Device Neural Intelligence" }
        trainingData = cleanText
        val uniqueChars = cleanText.toSet().toList().sorted()
        val model = CharRNNModel(uniqueChars, hiddenSize.coerceIn(16, 512), seed)
        activeModel = model
        _telemetry.update {
            it.copy(
                totalParameters = model.totalParameterCount,
                hiddenSize = model.hiddenSize,
                vocabSize = model.vocabSize,
                statusMessage = "Model kustom dibuat (${model.totalParameterCount} parameter nyata, ${model.vocabSize} vocab, hidden ${model.hiddenSize})"
            )
        }
        return model
    }

    /**
     * Loads an external or deserialized model into active memory.
     */
    fun loadModel(model: CharRNNModel, sourceLabel: String = "Model Checkpoint") {
        activeModel = model
        _telemetry.update {
            it.copy(
                totalParameters = model.totalParameterCount,
                hiddenSize = model.hiddenSize,
                vocabSize = model.vocabSize,
                statusMessage = "$sourceLabel dimuat (${model.totalParameterCount} params)"
            )
        }
    }

    /**
     * Starts or continues training loop using real BPTT gradients and cross-entropy loss.
     */
    fun startTraining(
        datasetText: String,
        hiddenSize: Int = 64,
        seqLen: Int = 32,
        lr: Float = 0.01f,
        epochs: Int = 5
    ) {
        if (trainingJob?.isActive == true) return

        val text = datasetText.ifBlank { DatasetRepository.activeDataset.value.text }
        trainingData = text

        if (activeModel == null || activeModel?.vocab?.toSet() != text.toSet()) {
            createCustomModel(hiddenSize, text)
        }

        val model = activeModel ?: return
        this.seqLength = seqLen.coerceIn(4, 128)
        this.learningRate = lr.coerceIn(0.0001f, 0.5f)
        this.targetEpochs = epochs.coerceIn(1, 100)

        if (text.length <= seqLength) {
            _telemetry.update {
                it.copy(statusMessage = "Dataset terlalu pendek untuk sequence length $seqLength")
            }
            return
        }

        val stepsPerEpoch = (text.length - seqLength).coerceAtLeast(1)
        val totalSteps = stepsPerEpoch * targetEpochs

        _telemetry.update {
            it.copy(
                status = TrainingStatus.RUNNING,
                totalSteps = totalSteps,
                totalEpochs = targetEpochs,
                statusMessage = "Melatih model secara real-time..."
            )
        }

        trainingJob = scope.launch(Dispatchers.Default) {
            try {
                var stepCount = _telemetry.value.currentStep
                val lossList = _telemetry.value.lossHistory.toMutableList()

                for (epoch in _telemetry.value.currentEpoch until targetEpochs) {
                    for (i in 0 until (text.length - seqLength - 1)) {
                        if (!isActive) break

                        val inputSlice = text.substring(i, i + seqLength)
                        val targetSlice = text.substring(i + 1, i + seqLength + 1)

                        val inputIndices = IntArray(seqLength) { model.charToIdx[inputSlice[it]] ?: 0 }
                        val targetIndices = IntArray(seqLength) { model.charToIdx[targetSlice[it]] ?: 0 }

                        val stepResult = model.trainStep(
                            inputIndices = inputIndices,
                            targetIndices = targetIndices,
                            learningRate = learningRate
                        )

                        stepCount++
                        if (lossList.size > 200) {
                            lossList.removeAt(0)
                        }
                        lossList.add(stepResult.loss)

                        val initLoss = _telemetry.value.initialLoss ?: stepResult.loss

                        _telemetry.update {
                            it.copy(
                                currentStep = stepCount,
                                currentEpoch = epoch + 1,
                                latestLoss = stepResult.loss,
                                initialLoss = initLoss,
                                lossHistory = lossList.toList(),
                                stepDurationMs = stepResult.durationMs,
                                gflops = stepResult.gflops,
                                gradientNorm = stepResult.gradientNorm,
                                isNdkAccelerated = stepResult.isNdkAccelerated,
                                statusMessage = "Epoch ${epoch + 1}/$targetEpochs - Step $stepCount/$totalSteps"
                            )
                        }

                        // Yield briefly to maintain responsive UI
                        delay(2)
                    }
                }

                _telemetry.update {
                    it.copy(
                        status = TrainingStatus.COMPLETED,
                        statusMessage = "Pelatihan selesai! Loss akhir: ${it.latestLoss ?: 0f}"
                    )
                }
            } catch (e: CancellationException) {
                _telemetry.update {
                    it.copy(statusMessage = "Pelatihan dihentikan sementara")
                }
            } catch (e: Exception) {
                _telemetry.update {
                    it.copy(
                        status = TrainingStatus.STOPPED,
                        statusMessage = "Error: ${e.message}"
                    )
                }
            }
        }
    }

    fun pauseTraining() {
        trainingJob?.cancel()
        trainingJob = null
        _telemetry.update {
            it.copy(status = TrainingStatus.PAUSED, statusMessage = "Pelatihan dijeda")
        }
    }

    fun resumeTraining() {
        if (_telemetry.value.status == TrainingStatus.PAUSED) {
            val model = activeModel ?: return
            val text = trainingData
            val stepsPerEpoch = (text.length - seqLength).coerceAtLeast(1)
            val totalSteps = stepsPerEpoch * targetEpochs

            _telemetry.update {
                it.copy(status = TrainingStatus.RUNNING, statusMessage = "Melanjutkan pelatihan...")
            }

            trainingJob = scope.launch(Dispatchers.Default) {
                try {
                    var stepCount = _telemetry.value.currentStep
                    val lossList = _telemetry.value.lossHistory.toMutableList()

                    for (epoch in (_telemetry.value.currentEpoch - 1).coerceAtLeast(0) until targetEpochs) {
                        for (i in (stepCount % stepsPerEpoch) until (text.length - seqLength - 1)) {
                            if (!isActive) break

                            val inputSlice = text.substring(i, i + seqLength)
                            val targetSlice = text.substring(i + 1, i + seqLength + 1)

                            val inputIndices = IntArray(seqLength) { model.charToIdx[inputSlice[it]] ?: 0 }
                            val targetIndices = IntArray(seqLength) { model.charToIdx[targetSlice[it]] ?: 0 }

                            val stepResult = model.trainStep(
                                inputIndices = inputIndices,
                                targetIndices = targetIndices,
                                learningRate = learningRate
                            )

                            stepCount++
                            if (lossList.size > 200) {
                                lossList.removeAt(0)
                            }
                            lossList.add(stepResult.loss)

                            _telemetry.update {
                                it.copy(
                                    currentStep = stepCount,
                                    currentEpoch = epoch + 1,
                                    latestLoss = stepResult.loss,
                                    lossHistory = lossList.toList(),
                                    stepDurationMs = stepResult.durationMs,
                                    gflops = stepResult.gflops,
                                    gradientNorm = stepResult.gradientNorm,
                                    isNdkAccelerated = stepResult.isNdkAccelerated,
                                    statusMessage = "Epoch ${epoch + 1}/$targetEpochs - Step $stepCount/$totalSteps"
                                )
                            }
                            delay(2)
                        }
                    }

                    _telemetry.update {
                        it.copy(
                            status = TrainingStatus.COMPLETED,
                            statusMessage = "Pelatihan selesai! Loss akhir: ${it.latestLoss ?: 0f}"
                        )
                    }
                } catch (e: CancellationException) {
                    _telemetry.update {
                        it.copy(statusMessage = "Pelatihan dijeda")
                    }
                } catch (e: Exception) {
                    _telemetry.update {
                        it.copy(
                            status = TrainingStatus.STOPPED,
                            statusMessage = "Error: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    fun stopTraining() {
        trainingJob?.cancel()
        trainingJob = null
        _telemetry.update {
            it.copy(
                status = TrainingStatus.STOPPED,
                statusMessage = "Pelatihan dihentikan oleh pengguna"
            )
        }
    }

    fun resetTraining() {
        stopTraining()
        _telemetry.update {
            TrainingTelemetry(
                totalParameters = activeModel?.totalParameterCount,
                hiddenSize = activeModel?.hiddenSize ?: 64,
                vocabSize = activeModel?.vocabSize ?: 0,
                statusMessage = "Pelatihan direset"
            )
        }
    }

    /**
     * Sends a user chat message and triggers real on-device neural text generation.
     */
    fun sendChatMessage(userText: String, temperature: Float = 0.8f, maxTokens: Int = 100) {
        val trimmed = userText.trim()
        if (trimmed.isBlank() || _isChatGenerating.value) return

        val userMsg = ChatMessage(
            id = "user_${System.currentTimeMillis()}",
            sender = ChatSender.USER,
            text = trimmed
        )
        _chatMessages.update { it + userMsg }

        _isChatGenerating.value = true

        scope.launch(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            val model = activeModel

            if (model == null) {
                _chatMessages.update {
                    it + ChatMessage(
                        id = "nexora_${System.currentTimeMillis()}",
                        sender = ChatSender.NEXORA,
                        text = "Model saraf belum dibuat. Silakan buka tab 'Model Studio' atau 'Training' terlebih dahulu untuk menginisialisasi arsitektur Nexora.",
                        latencyMs = 0,
                        tokensGenerated = 0,
                        tokensPerSec = 0f,
                        isNdkUsed = false
                    )
                }
                _isChatGenerating.value = false
                return@launch
            }

            // Real forward autoregressive inference from weights
            val generatedFull = model.generate(
                seedText = trimmed,
                length = maxTokens.coerceIn(10, 300),
                temperature = temperature
            )

            // Extract completion part
            val completion = if (generatedFull.startsWith(trimmed)) {
                generatedFull.substring(trimmed.length).trimStart()
            } else {
                generatedFull
            }

            val responseText = completion.ifBlank {
                "..."
            }

            val endTime = System.currentTimeMillis()
            val durationMs = (endTime - startTime).coerceAtLeast(1)
            val tokensCount = responseText.length
            val tokensPerSec = (tokensCount.toFloat() / (durationMs.toFloat() / 1000f)).coerceAtLeast(0f)

            _chatMessages.update {
                it + ChatMessage(
                    id = "nexora_${System.currentTimeMillis()}",
                    sender = ChatSender.NEXORA,
                    text = responseText,
                    tokensGenerated = tokensCount,
                    latencyMs = durationMs,
                    tokensPerSec = tokensPerSec,
                    isNdkUsed = NdkNeuralAccelerator.isNdkAvailable
                )
            }

            _isChatGenerating.value = false
        }
    }

    fun clearChat() {
        _chatMessages.value = listOf(
            ChatMessage(
                id = "cleared_${System.currentTimeMillis()}",
                sender = ChatSender.SYSTEM,
                text = "Riwayat percakapan telah dibersihkan."
            )
        )
    }
}
