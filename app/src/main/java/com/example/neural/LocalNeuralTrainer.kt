package com.example.neural

import android.content.Context
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

    var trainingData: String = ""
        private set
    var seqLength: Int = 32
        private set
    var learningRate: Float = 0.01f
        private set
    var targetEpochs: Int = 5
        private set

    init {
        // Automatically initialize model with default dataset
        val defaultDataset = DatasetRepository.activeDataset.value
        createCustomModel(hiddenSize = 64, datasetText = defaultDataset.text)
    }

    /**
     * Single canonical formula for number of valid sequence windows per epoch.
     * Given text of length L and sequence length S:
     * Valid start index i satisfies: i + 1 + S <= L ==> i <= L - S - 1.
     * Count of valid indices 0 .. (L - S - 1) is: (L - S - 1) - 0 + 1 = L - S.
     */
    fun calculateStepsPerEpoch(textLength: Int, seqLen: Int): Int {
        return (textLength - seqLen).coerceAtLeast(0)
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
     * Starts a new training loop using real BPTT gradients and cross-entropy loss.
     */
    fun startTraining(
        datasetText: String,
        hiddenSize: Int = 64,
        seqLen: Int = 32,
        lr: Float = 0.01f,
        epochs: Int = 5,
        context: Context? = null
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

        val stepsPerEpoch = calculateStepsPerEpoch(text.length, seqLength)
        if (stepsPerEpoch <= 0) {
            _telemetry.update {
                it.copy(statusMessage = "Dataset terlalu pendek (${text.length} char) untuk sequence length $seqLength")
            }
            return
        }

        val totalSteps = stepsPerEpoch * targetEpochs

        _telemetry.update {
            it.copy(
                status = TrainingStatus.RUNNING,
                currentStep = 0,
                totalSteps = totalSteps,
                currentEpoch = 0,
                totalEpochs = targetEpochs,
                lossHistory = emptyList(),
                statusMessage = "Melatih model secara real-time..."
            )
        }

        runTrainingLoop(
            model = model,
            text = text,
            stepsPerEpoch = stepsPerEpoch,
            totalSteps = totalSteps,
            initialStepCount = 0,
            context = context
        )
    }

    /**
     * Resumes training from the exact saved step position.
     */
    fun resumeTraining(context: Context? = null) {
        if (_telemetry.value.status != TrainingStatus.PAUSED && _telemetry.value.status != TrainingStatus.STOPPED) {
            return
        }

        val model = activeModel ?: return
        val text = trainingData
        val stepsPerEpoch = calculateStepsPerEpoch(text.length, seqLength)
        if (stepsPerEpoch <= 0) return

        val totalSteps = stepsPerEpoch * targetEpochs
        val currentStep = _telemetry.value.currentStep

        if (currentStep >= totalSteps) {
            _telemetry.update {
                it.copy(
                    status = TrainingStatus.COMPLETED,
                    statusMessage = "Pelatihan telah selesai untuk seluruh $targetEpochs epoch."
                )
            }
            return
        }

        _telemetry.update {
            it.copy(
                status = TrainingStatus.RUNNING,
                totalSteps = totalSteps,
                totalEpochs = targetEpochs,
                statusMessage = "Melanjutkan pelatihan dari step $currentStep/$totalSteps..."
            )
        }

        runTrainingLoop(
            model = model,
            text = text,
            stepsPerEpoch = stepsPerEpoch,
            totalSteps = totalSteps,
            initialStepCount = currentStep,
            context = context
        )
    }

    private fun runTrainingLoop(
        model: CharRNNModel,
        text: String,
        stepsPerEpoch: Int,
        totalSteps: Int,
        initialStepCount: Int,
        context: Context?
    ) {
        trainingJob?.cancel()

        trainingJob = scope.launch(Dispatchers.Default) {
            try {
                var stepCount = initialStepCount
                val lossList = _telemetry.value.lossHistory.toMutableList()
                val startEpoch = stepCount / stepsPerEpoch
                var periodicSaveCounter = 0
                var lastUiUpdateMs = System.currentTimeMillis()
                val trainingStartWallTime = System.currentTimeMillis()

                if (context != null) {
                    NeuralTrainingForegroundService.startService(
                        context = context,
                        epoch = startEpoch + 1,
                        totalEpochs = targetEpochs,
                        step = stepCount,
                        totalSteps = totalSteps,
                        loss = _telemetry.value.latestLoss ?: 0f
                    )
                }

                for (epoch in startEpoch until targetEpochs) {
                    val startStepInEpoch = if (epoch == startEpoch) stepCount % stepsPerEpoch else 0

                    for (i in startStepInEpoch until stepsPerEpoch) {
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
                        if (lossList.size > 250) {
                            lossList.removeAt(0)
                        }
                        lossList.add(stepResult.loss)

                        val initLoss = _telemetry.value.initialLoss ?: stepResult.loss

                        val now = System.currentTimeMillis()
                        val shouldUpdateUi = (stepCount % 5 == 0) || (now - lastUiUpdateMs >= 60) || (stepCount >= totalSteps)

                        if (shouldUpdateUi) {
                            lastUiUpdateMs = now
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

                            if (context != null && stepCount % 25 == 0) {
                                NeuralTrainingForegroundService.updateProgress(
                                    context = context,
                                    epoch = epoch + 1,
                                    totalEpochs = targetEpochs,
                                    step = stepCount,
                                    totalSteps = totalSteps,
                                    loss = stepResult.loss
                                )
                            }
                        }

                        // Periodic atomic snapshot save every 100 steps if context is available
                        periodicSaveCounter++
                        if (context != null && periodicSaveCounter % 100 == 0) {
                            saveActiveSession(context)
                        }
                    }
                }

                if (stepCount >= totalSteps) {
                    _telemetry.update {
                        it.copy(
                            status = TrainingStatus.COMPLETED,
                            statusMessage = "Pelatihan selesai! Loss akhir: ${it.latestLoss ?: 0f}"
                        )
                    }
                    if (context != null) {
                        NeuralTrainingForegroundService.stopService(context)
                        try {
                            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                            if (auth.currentUser != null) {
                                val sync = FirestoreSyncManager.create(context)
                                val durationSec = ((System.currentTimeMillis() - trainingStartWallTime) / 1000L).coerceAtLeast(1L)
                                sync.recordTrainingRun(
                                    datasetName = "Dataset On-Device",
                                    hiddenSize = model.hiddenSize,
                                    seqLen = seqLength,
                                    learningRate = learningRate,
                                    targetEpochs = targetEpochs,
                                    totalSteps = totalSteps,
                                    finalLoss = _telemetry.value.latestLoss ?: 0f,
                                    initialLoss = _telemetry.value.initialLoss ?: 0f,
                                    isNdkAccelerated = _telemetry.value.isNdkAccelerated,
                                    durationSeconds = durationSec
                                )
                            }
                        } catch (e: Exception) {
                            // Non-fatal cloud sync
                        }
                        SessionPersistenceManager.clearSession(context)
                    }
                }
            } catch (e: CancellationException) {
                _telemetry.update {
                    it.copy(statusMessage = "Pelatihan dijeda")
                }
                if (context != null) {
                    NeuralTrainingForegroundService.stopService(context)
                }
            } catch (e: Exception) {
                _telemetry.update {
                    it.copy(
                        status = TrainingStatus.STOPPED,
                        statusMessage = "Error: ${e.message}"
                    )
                }
                if (context != null) {
                    NeuralTrainingForegroundService.stopService(context)
                }
            }
        }
    }

    fun pauseTraining(context: Context? = null) {
        trainingJob?.cancel()
        trainingJob = null
        _telemetry.update {
            it.copy(status = TrainingStatus.PAUSED, statusMessage = "Pelatihan dijeda")
        }
        if (context != null) {
            NeuralTrainingForegroundService.stopService(context)
            saveActiveSession(context)
        }
    }

    fun stopTraining(context: Context? = null) {
        trainingJob?.cancel()
        trainingJob = null
        _telemetry.update {
            it.copy(
                status = TrainingStatus.STOPPED,
                statusMessage = "Pelatihan dihentikan oleh pengguna"
            )
        }
        if (context != null) {
            NeuralTrainingForegroundService.stopService(context)
            SessionPersistenceManager.clearSession(context)
        }
    }

    fun resetTraining(context: Context? = null) {
        stopTraining(context)
        _telemetry.update {
            TrainingTelemetry(
                totalParameters = activeModel?.totalParameterCount,
                hiddenSize = activeModel?.hiddenSize ?: 64,
                vocabSize = activeModel?.vocabSize ?: 0,
                statusMessage = "Pelatihan direset"
            )
        }
        if (context != null) {
            SessionPersistenceManager.clearSession(context)
        }
    }

    /**
     * Creates a durable snapshot of the current training session.
     */
    fun createSessionSnapshot(datasetName: String = "Dataset Aktif"): TrainingSessionSnapshot? {
        val model = activeModel ?: return null
        val tele = _telemetry.value
        return TrainingSessionSnapshot(
            version = SessionPersistenceManager.SESSION_VERSION,
            currentStep = tele.currentStep,
            totalSteps = tele.totalSteps,
            currentEpoch = tele.currentEpoch,
            totalEpochs = tele.totalEpochs,
            seqLength = seqLength,
            learningRate = learningRate,
            latestLoss = tele.latestLoss,
            initialLoss = tele.initialLoss,
            lossHistory = tele.lossHistory,
            datasetName = datasetName,
            datasetText = trainingData,
            modelBytes = model.serializeToBytes(),
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Persists active session snapshot to disk.
     */
    fun saveActiveSession(context: Context, datasetName: String = "Dataset Aktif"): Boolean {
        val snapshot = createSessionSnapshot(datasetName) ?: return false
        scope.launch(Dispatchers.IO) {
            SessionPersistenceManager.saveSession(context, snapshot)
        }
        return true
    }

    /**
     * Restores state from a durable snapshot.
     */
    fun restoreFromSnapshot(snapshot: TrainingSessionSnapshot) {
        val restoredModel = CharRNNModel.deserializeFromBytes(snapshot.modelBytes)
        activeModel = restoredModel
        trainingData = snapshot.datasetText
        seqLength = snapshot.seqLength
        learningRate = snapshot.learningRate
        targetEpochs = snapshot.totalEpochs

        _telemetry.update {
            TrainingTelemetry(
                status = TrainingStatus.PAUSED,
                currentStep = snapshot.currentStep,
                totalSteps = snapshot.totalSteps,
                currentEpoch = snapshot.currentEpoch,
                totalEpochs = snapshot.totalEpochs,
                latestLoss = snapshot.latestLoss,
                initialLoss = snapshot.initialLoss,
                lossHistory = snapshot.lossHistory,
                totalParameters = restoredModel.totalParameterCount,
                hiddenSize = restoredModel.hiddenSize,
                vocabSize = restoredModel.vocabSize,
                statusMessage = "Sesi dipulihkan dari snapshot (Step ${snapshot.currentStep}/${snapshot.totalSteps})"
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
                        text = "Model saraf belum dibuat. Silakan buka tab 'Studio' atau 'Training' terlebih dahulu untuk menginisialisasi arsitektur Nexora.",
                        latencyMs = 0,
                        tokensGenerated = 0,
                        tokensPerSec = 0f,
                        isNdkUsed = false
                    )
                }
                _isChatGenerating.value = false
                return@launch
            }

            // Real forward autoregressive inference from weights with aligned next-char state
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
