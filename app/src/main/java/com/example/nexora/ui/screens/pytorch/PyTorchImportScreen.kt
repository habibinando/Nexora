package com.example.nexora.ui.screens.pytorch

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nexora.ui.components.NexoraHeader
import com.example.nexora.ui.components.StatusBadge
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.ui.theme.*

@Composable
fun PyTorchImportScreen(
    viewModel: NexoraViewModel,
    onBack: () -> Unit,
    onNavigateToTraining: () -> Unit,
    onOpenMenu: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    val activeModel by viewModel.activeModelInfo.collectAsStateWithLifecycle()
    val isOffline by viewModel.offlineEnforced.collectAsStateWithLifecycle()
    val importResult by viewModel.pyTorchImportResult.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImportingPyTorch.collectAsStateWithLifecycle()
    val trainingProgress by viewModel.localTrainingProgress.collectAsStateWithLifecycle()

    var isApplyingWeights by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Menganalisis berkas PyTorch asli...", Toast.LENGTH_SHORT).show()
            viewModel.importPyTorchFile(uri) { success, message ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            NexoraHeader(
                title = "Impor PyTorch (.pt)",
                activeModelName = activeModel.name,
                isOffline = isOffline,
                onBack = onBack,
                onMenuClick = onOpenMenu
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
        ) {
            // 1. Kartu Transparansi & Penjelasan Teknis Nyata (Sesuai Aturan Keras)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NexoraViolet.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = NexoraViolet,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Bisa Impor Model .pt untuk Lanjut Training di HP?",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        Text(
                            text = "Berikut penjelasan teknis yang jujur mengenai kemampuan komputasi perangkat mobile:",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            lineHeight = 16.sp
                        )

                        Surface(
                            color = DarkSurfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "1. Batasan Teknis Perangkat Mobile (HP):",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = NexoraAmber
                                )
                                Text(
                                    text = "• Compiler autograd PyTorch Python murni (torch) tidak dapat berjalan secara native di Android.\n" +
                                            "• Runtime PyTorch Mobile / ExecuTorch di Android hanya didesain untuk INFERENSI, bukan untuk training (backward pass) model sembarang.\n" +
                                            "• Melatih model multi-miliar parameter (LLaMA/Mistral) membutuhkan VRAM puluhan GB yang mustahil dilakukan di HP tanpa crash kehabisan memori (OOM).",
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    lineHeight = 15.sp
                                )

                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "2. Solusi Nyata & Jembatan yang Disediakan Aplikasi Ini:",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = NexoraCyan
                                )
                                Text(
                                    text = "• Aplikasi ini membedah langsung isi biner dari file .pt/.pth (PKZip archive), binary float buffer, atau JSON state_dict yang diekspor dari PyTorch.\n" +
                                            "• Mengekstrak bobot matriks asli (w_emb, w_xh, w_hh, linear weights), menghitung parameter asli dan norma Frobenius L2 secara nyata.\n" +
                                            "• Memetakan tensor ke arsitektur jaringan saraf on-device, sehingga Anda dapat LANGSUNG MELANJUTKAN TRAINING (backpropagation & optimizer AdamW) di HP!",
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }

            // 2. Tombol Aksi Impor Berkas PyTorch
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NexoraCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Pilih Berkas Checkpoint PyTorch",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Mendukung berkas biner .pt, .pth, .pte, .bin, atau format export state_dict .json dari PyTorch.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )

                        Button(
                            onClick = {
                                filePickerLauncher.launch(
                                    arrayOf(
                                        "*/*",
                                        "application/octet-stream",
                                        "application/json",
                                        "application/zip"
                                    )
                                )
                            },
                            enabled = !isImporting && !isApplyingWeights,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NexoraCyan,
                                contentColor = DarkBackground
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("pick_pytorch_file_button")
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(
                                    color = DarkBackground,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Menganalisis Biner Tensor...", fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Pilih Berkas PyTorch (.pt / .pth / .json)", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 3. Hasil Analisis Biner Nyata (No Mock, Real Data atau "belum ada")
            item {
                val currentResult = importResult
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkOutline.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Hasil Analisis Biner Tensor",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            if (currentResult != null && currentResult.isSuccess) {
                                StatusBadge(text = "Valid & Terbaca", color = NexoraEmerald)
                            } else {
                                StatusBadge(text = "Belum Ada Berkas", color = TextMuted)
                            }
                        }

                        if (currentResult == null) {
                            // Sesuai aturan keras: kalau belum ada data, tampilkan "belum ada"
                            Surface(
                                color = DarkSurfaceVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier.padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "belum ada berkas PyTorch yang diimpor.",
                                        color = TextMuted,
                                        fontSize = 12.sp,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                    )
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PyTorchInfoRow("Nama Berkas", currentResult.fileName)
                                PyTorchInfoRow(
                                    "Ukuran Asli",
                                    if (currentResult.fileSizeBytes > 0) "${currentResult.fileSizeBytes / 1024} KB (${currentResult.fileSizeBytes} byte)" else "belum ada"
                                )
                                PyTorchInfoRow("Format Terdeteksi", currentResult.detectedArchitecture)
                                PyTorchInfoRow(
                                    "Total Parameter Nyata",
                                    if (currentResult.totalParameters > 0) "${currentResult.totalParameters} parameter terhitung" else "0 (belum ada)"
                                )
                                PyTorchInfoRow(
                                    "Norma L2 (Frobenius)",
                                    if (currentResult.frobeniusNorm > 0f) "%.4f (dihitung dari sqrt(sum(w²)))".format(currentResult.frobeniusNorm) else "belum ada"
                                )
                                PyTorchInfoRow(
                                    "Jumlah Tensor",
                                    if (currentResult.tensorCount > 0) "${currentResult.tensorCount} tensor" else "0"
                                )

                                if (currentResult.layerShapes.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "Dimensi Layer / Tensor yang Diekstrak:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NexoraCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(DarkSurfaceVariant.copy(alpha = 0.5f))
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        currentResult.layerShapes.forEach { (layerName, shape) ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = layerName,
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = TextPrimary
                                                )
                                                Text(
                                                    text = shape,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NexoraViolet
                                                )
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = currentResult.summary,
                                    fontSize = 12.sp,
                                    color = if (currentResult.isSuccess) NexoraCyan else NexoraRose,
                                    lineHeight = 16.sp
                                )

                                if (currentResult.isReadyToContinueTraining) {
                                    Spacer(Modifier.height(6.dp))
                                    Button(
                                        onClick = {
                                            isApplyingWeights = true
                                            viewModel.applyImportedPyTorchWeights { success, msg ->
                                                isApplyingWeights = false
                                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                                if (success) {
                                                    onNavigateToTraining()
                                                }
                                            }
                                        },
                                        enabled = !isApplyingWeights,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NexoraEmerald,
                                            contentColor = DarkBackground
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("apply_pytorch_weights_button")
                                    ) {
                                        if (isApplyingWeights) {
                                            CircularProgressIndicator(
                                                color = DarkBackground,
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text("Menerapkan Bobot...", fontWeight = FontWeight.Bold)
                                        } else {
                                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Terapkan Bobot & Lanjut Training", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Panduan Ekspor Script PyTorch Python
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkOutline.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Panduan Ekspor dari PyTorch di Komputer / Server",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Jalankan skrip Python berikut di laptop/PC Anda untuk menyimpan state_dict ke format yang langsung kompatibel dengan aplikasi ini:",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 15.sp
                        )

                        Surface(
                            color = DarkBackground,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = """
import torch
import json

# Cara 1: Simpan standard PyTorch .pt
torch.save(model.state_dict(), "model_weights.pt")

# Cara 2: Simpan JSON float array (kompatibilitas 100%)
state_dict = model.state_dict()
export_dict = {k: v.cpu().numpy().tolist() for k, v in state_dict.items()}
with open("model_weights.json", "w") as f:
    json.dump(export_dict, f)
                                """.trimIndent(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = NexoraCyan,
                                modifier = Modifier.padding(12.dp),
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PyTorchInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = TextSecondary
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
    }
}
