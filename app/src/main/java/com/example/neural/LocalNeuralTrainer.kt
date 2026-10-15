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
    val statusMessage: String = "Siap memulai pelatihan on-device"
)

class LocalNeuralTrainer(
    private val scope: CoroutineScope
) {
    private val _telemetry = MutableStateFlow(TrainingTelemetry())
    val telemetry: StateFlow<TrainingTelemetry> = _telemetry.asStateFlow()

    private var trainingJob: Job? = null
    var activeModel: CharRNNModel? = null
        private set

    private var trainingData: String = ""
    private var seqLength: Int = 32
    private var learningRate: Float = 0.01f
    private var targetEpochs: Int = 5

    fun initializeModel(datasetText: String, hiddenSize: Int = 64) {
        val cleanText = datasetText.ifBlank { "NeuralAI on-device training engine" }
        trainingData = cleanText
        val uniqueChars = cleanText.toSet().toList().sorted()
        val model = CharRNNModel(uniqueChars, hiddenSize)
        activeModel = model
        _telemetry.update {
            it.copy(
                totalParameters = model.totalParameterCount,
                statusMessage = "Model diinisialisasi (${model.totalParameterCount} parameter nyata)"
            )
        }
    }

    fun startTraining(
        datasetText: String,
        hiddenSize: Int = 64,
        seqLen: Int = 32,
        lr: Float = 0.01f,
        epochs: Int = 5
    ) {
        if (trainingJob?.isActive == true) return

        if (activeModel == null || activeModel?.vocab?.toSet() != datasetText.toSet()) {
            initializeModel(datasetText, hiddenSize)
        }

        val model = activeModel ?: return
        this.seqLength = seqLen.coerceIn(4, 128)
        this.learningRate = lr.coerceIn(0.0001f, 0.5f)
        this.targetEpochs = epochs.coerceIn(1, 100)

        val text = trainingData
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
        trainingJob?.cancel()
        trainingJob = null
        activeModel = null
        _telemetry.value = TrainingTelemetry(
            statusMessage = "Status reset. Siap memulai pelatihan baru."
        )
    }
}
