package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.neural.DatasetRepository
import com.example.neural.LocalNeuralTrainer
import com.example.neural.ModelCheckpoint
import com.example.neural.ModelCheckpointManager
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceCard
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeuralGreen
import com.example.ui.theme.DangerRed
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.util.Locale

@Composable
fun ModelStudioScreen(
    trainer: LocalNeuralTrainer,
    onNavigateToChat: () -> Unit
) {
    val context = LocalContext.current
    val telemetry by trainer.telemetry.collectAsState()
    val activeDataset by DatasetRepository.activeDataset.collectAsState()

    var hiddenSize by remember { mutableIntStateOf(telemetry.hiddenSize.coerceIn(16, 256)) }
    var checkpoints by remember { mutableStateOf<List<ModelCheckpoint>>(emptyList()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveNameInput by remember { mutableStateOf("") }

    fun refreshCheckpoints() {
        checkpoints = ModelCheckpointManager.listCheckpoints(context)
    }

    LaunchedEffect(Unit) {
        refreshCheckpoints()
    }

    // SAF Document Creator for Model Export (.nexora)
    val exportModelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            val model = trainer.activeModel
            if (model != null) {
                val success = ModelCheckpointManager.exportModelToUri(context, model, uri)
                statusMessage = if (success) {
                    "Model berhasil diekspor ke file eksternal (${ModelCheckpointManager.formatBytes(model.serializeToBytes().size.toLong())})"
                } else {
                    "Gagal menulis file model ke penyimpanan."
                }
            }
        }
    }

    // SAF Document Picker for Model Import (.nexora / .bin)
    val importModelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val result = ModelCheckpointManager.importModelFromUri(context, uri)
            if (result.isSuccess) {
                val model = result.getOrThrow()
                trainer.loadModel(model, "Model Impor")
                hiddenSize = model.hiddenSize
                statusMessage = "Berhasil memuat model: ${model.totalParameterCount} params (Vocab: ${model.vocabSize}, Hidden: ${model.hiddenSize})"
                refreshCheckpoints()
            } else {
                statusMessage = "Gagal memuat model: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    // Real dynamic parameter calculation
    val v = activeDataset.uniqueVocabSize
    val h = hiddenSize
    val wxhCount = h * v
    val whhCount = h * h
    val whyCount = v * h
    val bhCount = h
    val byCount = v
    val calculatedTotalParams = (wxhCount + whhCount + whyCount + bhCount + byCount).toLong()
    val estimatedMemoryBytes = calculatedTotalParams * 4L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Title
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Architecture, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Model Studio & Arsitektur", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text(
            "Rancang arsitektur model saraf kustom, hitung alokasi memori bobot nyata, serta ekspor & impor file bobot .nexora.",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        // Status banner
        statusMessage?.let { msg ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Text(
                    text = msg,
                    color = NeuralGreen,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Section 1: Architecture Builder
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Desain Arsitektur Model", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dimensi Hidden Layer (H): $hiddenSize", color = TextPrimary, fontSize = 13.sp)
                    Text("Matriks Whh: ${hiddenSize}x$hiddenSize", color = ElectricCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }

                Slider(
                    value = hiddenSize.toFloat(),
                    onValueChange = { hiddenSize = it.toInt() },
                    valueRange = 16f..256f,
                    steps = 15,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan,
                        inactiveTrackColor = CyberSurface
                    )
                )

                // Quick presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(32, 64, 128, 256).forEach { presetH ->
                        FilterChip(
                            selected = hiddenSize == presetH,
                            onClick = { hiddenSize = presetH },
                            label = { Text("H=$presetH", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonPurple,
                                selectedLabelColor = Color.White,
                                containerColor = CyberSurface
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Math breakdown card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CyberSurface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Perhitungan Parameter Nyata:", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("• Wxh (Input Embedding) : $h × $v = $wxhCount bobot", color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("• Whh (Konteks Rekuren)  : $h × $h = $whhCount bobot", color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("• Why (Output Proyeksi) : $v × $h = $whyCount bobot", color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("• Bias (bh + by)        : $h + $v = ${bhCount + byCount} bobot", color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Total Parameter: $calculatedTotalParams", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Ukuran Memori: ${ModelCheckpointManager.formatBytes(estimatedMemoryBytes)}", color = NeuralGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        trainer.createCustomModel(hiddenSize = hiddenSize)
                        statusMessage = "Model baru dengan $calculatedTotalParams parameter berhasil dibuat dan diinisialisasi!"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("create_model_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Buat & Inisialisasi Model Baru", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Section 2: Model Load & Export Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Ekspor & Impor Model (.nexora)", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Simpan bobot terlatih ke penyimpanan HP atau muat checkpoint dari file biner.", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Export to SAF
                    Button(
                        onClick = {
                            val defaultName = "nexora_model_h${trainer.activeModel?.hiddenSize ?: 64}.nexora"
                            exportModelLauncher.launch(defaultName)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("export_model_saf_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ekspor File", fontSize = 12.sp)
                    }

                    // Import from SAF
                    Button(
                        onClick = {
                            importModelLauncher.launch("*/*")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("import_model_saf_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberSurface, contentColor = ElectricCyan),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Muat File", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Save to local app storage
                Button(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .testTag("save_local_checkpoint_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSurface, contentColor = TextPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Simpan ke Checkpoint Lokal", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Section 3: Saved Checkpoints List
        Text("Daftar Checkpoint Tersimpan", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(modifier = Modifier.height(8.dp))

        if (checkpoints.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard)
            ) {
                Text(
                    text = "Belum ada checkpoint model yang disimpan secara lokal.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                checkpoints.forEach { cp ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(cp.filename, color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(cp.formattedSize, color = NeuralGreen, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${cp.formattedDate} • ${cp.parametersCount} params • H=${cp.hiddenSize}, V=${cp.vocabSize}",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                            if (cp.checksum.isNotBlank()) {
                                Text("Checksum: ${cp.checksum}", color = TextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val loaded = ModelCheckpointManager.loadCheckpoint(cp.filePath)
                                        if (loaded != null) {
                                            trainer.loadModel(loaded, cp.filename)
                                            statusMessage = "Checkpoint '${cp.filename}' berhasil dimuat ke memori aktif!"
                                        }
                                    },
                                    modifier = Modifier.height(34.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Muat", fontSize = 11.sp, color = ElectricCyan)
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                IconButton(
                                    onClick = {
                                        val shareIntent = ModelCheckpointManager.createShareIntent(context, cp)
                                        context.startActivity(android.content.Intent.createChooser(shareIntent, "Bagikan Model Nexora"))
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Bagikan", tint = NeonPurple, modifier = Modifier.size(16.dp))
                                }

                                IconButton(
                                    onClick = {
                                        ModelCheckpointManager.deleteCheckpoint(cp.filePath)
                                        refreshCheckpoints()
                                        statusMessage = "Checkpoint '${cp.filename}' dihapus."
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = DangerRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Save Dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Simpan Checkpoint Nexora", color = TextPrimary) },
            text = {
                Column {
                    Text("Beri nama untuk file checkpoint model:", color = TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = saveNameInput,
                        onValueChange = { saveNameInput = it },
                        placeholder = { Text("contoh: nexora_chat_v1") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = ElectricCyan
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val model = trainer.activeModel
                        if (model != null) {
                            val cp = ModelCheckpointManager.saveCheckpoint(context, model, saveNameInput)
                            refreshCheckpoints()
                            statusMessage = "Checkpoint tersimpan: ${cp.filename} (${cp.formattedSize})"
                        }
                        showSaveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black)
                ) {
                    Text("Simpan")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSaveDialog = false }) {
                    Text("Batal")
                }
            },
            containerColor = CyberSurfaceCard
        )
    }
}
