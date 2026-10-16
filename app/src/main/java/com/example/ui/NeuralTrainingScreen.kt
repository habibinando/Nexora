package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.neural.LocalNeuralTrainer
import com.example.neural.ModelCheckpointManager
import com.example.neural.NdkNeuralAccelerator
import com.example.neural.TrainingStatus
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceCard
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.DangerRed
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeuralGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NeuralTrainingScreen(
    trainer: LocalNeuralTrainer,
    onNavigateToInference: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val telemetry by trainer.telemetry.collectAsState()

    // Dataset from DatasetRepository
    val activeDatasetInfo by com.example.neural.DatasetRepository.activeDataset.collectAsState()
    var datasetText by remember { mutableStateOf(activeDatasetInfo.text) }

    val presetA = com.example.neural.DatasetRepository.INDONESIAN_CONVERSATIONAL
    val presetB = com.example.neural.DatasetRepository.TECH_AI_KNOWLEDGE
    val presetC = com.example.neural.DatasetRepository.KOTLIN_CODE_CORPUS
    var selectedPresetIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(activeDatasetInfo) {
        datasetText = activeDatasetInfo.text
    }

    var hiddenDimension by remember { mutableIntStateOf(64) }
    var sequenceLength by remember { mutableIntStateOf(32) }
    var learningRate by remember { mutableFloatStateOf(0.015f) }
    var epochCount by remember { mutableIntStateOf(5) }

    var showSaveDialog by remember { mutableStateOf(false) }
    var checkpointNameInput by remember { mutableStateOf("") }
    var saveResultMessage by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyberBackground)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // NDK Accelerator Status Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(ElectricCyan, NeonPurple))),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(if (NdkNeuralAccelerator.isNdkAvailable) NeuralGreen.copy(alpha = 0.2f) else AccentOrange.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (NdkNeuralAccelerator.isNdkAvailable) Icons.Default.Bolt else Icons.Default.Memory,
                        contentDescription = "Status Hardware Akselerator",
                        tint = if (NdkNeuralAccelerator.isNdkAvailable) NeuralGreen else AccentOrange,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (NdkNeuralAccelerator.isNdkAvailable) "NDK Native Engine Aktif" else "JVM Fallback Engine",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = NdkNeuralAccelerator.getNdkHardwareInfo(),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2
                    )
                }
            }
        }

        // Training Dataset Selection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Korpus Dataset Pelatihan",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Teks ini digunakan untuk melatih bobot neural on-device",
                    color = TextTertiary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedPresetIndex == 0,
                        onClick = {
                            selectedPresetIndex = 0
                            datasetText = presetA
                        },
                        label = { Text("Teks Filosofis (ID)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                            selectedLabelColor = ElectricCyan
                        ),
                        modifier = Modifier.testTag("preset_id_chip")
                    )

                    FilterChip(
                        selected = selectedPresetIndex == 1,
                        onClick = {
                            selectedPresetIndex = 1
                            datasetText = presetB
                        },
                        label = { Text("Kode Python") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                            selectedLabelColor = ElectricCyan
                        ),
                        modifier = Modifier.testTag("preset_code_chip")
                    )

                    FilterChip(
                        selected = selectedPresetIndex == 2,
                        onClick = {
                            selectedPresetIndex = 2
                            datasetText = presetC
                        },
                        label = { Text("English Classic") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                            selectedLabelColor = ElectricCyan
                        ),
                        modifier = Modifier.testTag("preset_en_chip")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = datasetText,
                    onValueChange = {
                        datasetText = it
                        selectedPresetIndex = -1
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .testTag("dataset_input_field"),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = BorderSubtle,
                        focusedContainerColor = CyberSurfaceVariant,
                        unfocusedContainerColor = CyberSurfaceVariant
                    ),
                    placeholder = { Text("Ketik atau tempel teks dataset...", color = TextTertiary) }
                )
            }
        }

        // Hyperparameter Tuning Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Ikon Hyperparameter",
                        tint = NeonPurple,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Konfigurasi Hyperparameter",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Hidden dimension
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Hidden Dimension (Dimensi Bobot):", color = TextSecondary, fontSize = 13.sp)
                    Text("$hiddenDimension neuron", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = hiddenDimension.toFloat(),
                    onValueChange = { hiddenDimension = (it.toInt() / 16) * 16 },
                    valueRange = 32f..128f,
                    steps = 5,
                    enabled = telemetry.status != TrainingStatus.RUNNING,
                    colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan),
                    modifier = Modifier.testTag("hidden_dim_slider")
                )

                // Sequence length
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sequence Length (Panjang Konteks):", color = TextSecondary, fontSize = 13.sp)
                    Text("$sequenceLength token", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = sequenceLength.toFloat(),
                    onValueChange = { sequenceLength = (it.toInt() / 8) * 8 },
                    valueRange = 16f..64f,
                    steps = 5,
                    enabled = telemetry.status != TrainingStatus.RUNNING,
                    colors = SliderDefaults.colors(thumbColor = NeonPurple, activeTrackColor = NeonPurple),
                    modifier = Modifier.testTag("seq_len_slider")
                )

                // Learning Rate
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Learning Rate (Laju Belajar):", color = TextSecondary, fontSize = 13.sp)
                    Text(String.format(Locale.US, "%.3f", learningRate), color = NeuralGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = learningRate,
                    onValueChange = { learningRate = it },
                    valueRange = 0.005f..0.05f,
                    enabled = telemetry.status != TrainingStatus.RUNNING,
                    colors = SliderDefaults.colors(thumbColor = NeuralGreen, activeTrackColor = NeuralGreen),
                    modifier = Modifier.testTag("learning_rate_slider")
                )

                // Epochs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Target Epoch:", color = TextSecondary, fontSize = 13.sp)
                    Text("$epochCount epoch", color = AccentOrange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = epochCount.toFloat(),
                    onValueChange = { epochCount = it.toInt() },
                    valueRange = 1f..15f,
                    steps = 13,
                    enabled = telemetry.status != TrainingStatus.RUNNING,
                    colors = SliderDefaults.colors(thumbColor = AccentOrange, activeTrackColor = AccentOrange),
                    modifier = Modifier.testTag("epoch_slider")
                )
            }
        }

        // Training Controls Action Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = telemetry.statusMessage,
                    color = if (telemetry.status == TrainingStatus.RUNNING) ElectricCyan else TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )

                if (telemetry.totalSteps > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val progress = (telemetry.currentStep.toFloat() / telemetry.totalSteps).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = ElectricCyan,
                        trackColor = CyberSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (telemetry.status) {
                        TrainingStatus.IDLE, TrainingStatus.COMPLETED, TrainingStatus.STOPPED -> {
                            Button(
                                onClick = {
                                    trainer.startTraining(
                                        datasetText = datasetText,
                                        hiddenSize = hiddenDimension,
                                        seqLen = sequenceLength,
                                        lr = learningRate,
                                        epochs = epochCount
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("start_training_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Mulai Training", modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Mulai Training", fontWeight = FontWeight.Bold)
                            }
                        }

                        TrainingStatus.RUNNING -> {
                            Button(
                                onClick = { trainer.pauseTraining() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("pause_training_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange, contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = "Jeda Training", modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Jeda")
                            }

                            Button(
                                onClick = { trainer.stopTraining() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("stop_training_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Hentikan Training", modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Hentikan")
                            }
                        }

                        TrainingStatus.PAUSED -> {
                            Button(
                                onClick = { trainer.resumeTraining() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("resume_training_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = NeuralGreen, contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Lanjutkan Training", modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Lanjutkan")
                            }

                            Button(
                                onClick = { trainer.stopTraining() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("stop_training_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Hentikan Training", modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Hentikan")
                            }
                        }
                    }

                    IconButton(
                        onClick = { trainer.resetTraining() },
                        modifier = Modifier
                            .size(48.dp)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                            .testTag("reset_training_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Training", tint = TextSecondary)
                    }
                }
            }
        }

        // Real Telemetry Metrics Grid (Strictly conforming to: No mock, display "belum ada" if empty)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Telemetri Komputasi Nyata",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Nilai dihitung murni dari operasi matriks & memori nyata",
                    color = TextTertiary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricTile(
                        label = "Loss Terkini",
                        value = telemetry.latestLoss?.let { String.format(Locale.US, "%.4f", it) } ?: "belum ada",
                        accentColor = ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )

                    MetricTile(
                        label = "Loss Awal",
                        value = telemetry.initialLoss?.let { String.format(Locale.US, "%.4f", it) } ?: "belum ada",
                        accentColor = NeonPurple,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricTile(
                        label = "Durasi Step",
                        value = telemetry.stepDurationMs?.let { String.format(Locale.US, "%.2f ms", it) } ?: "belum ada",
                        accentColor = NeuralGreen,
                        modifier = Modifier.weight(1f)
                    )

                    MetricTile(
                        label = "Performa Step",
                        value = telemetry.gflops?.let { String.format(Locale.US, "%.2f GFLOPS", it) } ?: "belum ada",
                        accentColor = AccentOrange,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricTile(
                        label = "Jumlah Parameter",
                        value = telemetry.totalParameters?.let { String.format(Locale.US, "%,d", it) } ?: "belum ada",
                        accentColor = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )

                    MetricTile(
                        label = "Norm Gradien",
                        value = telemetry.gradientNorm?.let { String.format(Locale.US, "%.4f", it) } ?: "belum ada",
                        accentColor = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Real-Time Loss Graph Canvas
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Kurva Loss Pelatihan (Real-Time)",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Visualisasi konvergensi gradien aktual per langkah",
                    color = TextTertiary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                val losses = telemetry.lossHistory
                if (losses.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .background(CyberSurfaceVariant, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Belum ada riwayat training.\nTekan Mulai Training untuk mengamati kurva loss.",
                            color = TextTertiary,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(CyberSurfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val minLoss = losses.minOrNull() ?: 0f
                            val maxLoss = (losses.maxOrNull() ?: 1f).coerceAtLeast(minLoss + 0.01f)
                            val range = maxLoss - minLoss

                            val stepX = size.width / (losses.size - 1).coerceAtLeast(1)

                            val path = Path()
                            losses.forEachIndexed { index, loss ->
                                val x = index * stepX
                                val normalizedY = 1f - ((loss - minLoss) / range)
                                val y = normalizedY * size.height
                                if (index == 0) {
                                    path.moveTo(x, y)
                                } else {
                                    path.lineTo(x, y)
                                }
                            }

                            drawPath(
                                path = path,
                                color = ElectricCyan,
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                    }
                }
            }
        }

        // Quick Save & Inference Action Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (trainer.activeModel != null) {
                        showSaveDialog = true
                    } else {
                        saveResultMessage = "Mulai atau inisialisasi training terlebih dahulu untuk menyimpan bobot."
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("save_checkpoint_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricCyan),
                border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(ElectricCyan, NeonPurple)))
            ) {
                Icon(Icons.Default.Save, contentDescription = "Simpan Adapter", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Simpan Adapter")
            }

            Button(
                onClick = onNavigateToInference,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("nav_to_inference_button"),
                colors = ButtonDefaults.buttonColors(containerColor = NeonPurple, contentColor = Color.White)
            ) {
                Icon(Icons.Default.Psychology, contentDescription = "Chat Nexora", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Chat Nexora")
            }
        }

        AnimatedVisibility(visible = saveResultMessage != null) {
            saveResultMessage?.let { msg ->
                Surface(
                    color = CyberSurfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        color = ElectricCyan,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }

    // Save Checkpoint Dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Simpan Checkpoint Adapter", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        "File adapter biner akan disimpan di penyimpanan internal perangkat dan dihitung ukuran byte pastinya.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = checkpointNameInput,
                        onValueChange = { checkpointNameInput = it },
                        placeholder = { Text("Nama file (opsional)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("checkpoint_name_field")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val model = trainer.activeModel
                        if (model != null) {
                            val cp = ModelCheckpointManager.saveCheckpoint(
                                context = context,
                                model = model,
                                customName = checkpointNameInput.ifBlank { null }
                            )
                            saveResultMessage = "Adapter tersimpan: ${cp.filename} (Ukuran nyata: ${cp.formattedSize}, ${cp.parametersCount} parameter)"
                        }
                        showSaveDialog = false
                    },
                    modifier = Modifier.testTag("confirm_save_checkpoint_button")
                ) {
                    Text("Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Batal")
                }
            },
            containerColor = CyberSurfaceCard
        )
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(CyberSurfaceVariant, RoundedCornerShape(8.dp))
            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Column {
            Text(
                text = label,
                color = TextTertiary,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                color = accentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
