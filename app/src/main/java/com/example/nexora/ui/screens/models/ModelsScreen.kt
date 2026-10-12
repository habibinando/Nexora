package com.example.nexora.ui.screens.models

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nexora.engine.model.CatalogModelEntry
import com.example.nexora.engine.model.ModelInstallStatus
import com.example.nexora.ui.components.NexoraHeader
import com.example.nexora.ui.components.StatusBadge
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.ui.theme.*

@Composable
fun ModelsScreen(
    viewModel: NexoraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val activeModel by viewModel.activeModelInfo.collectAsStateWithLifecycle()
    val deviceMem by viewModel.deviceMemory.collectAsStateWithLifecycle()
    val isOffline by viewModel.offlineEnforced.collectAsStateWithLifecycle()
    val isGpuActive by viewModel.gpuEnabled.collectAsStateWithLifecycle()
    val activeCpuThreads by viewModel.cpuThreads.collectAsStateWithLifecycle()
    val hardwareInfo by viewModel.hardwareInfo.collectAsStateWithLifecycle()

    val catalogModels by viewModel.catalogModels.collectAsStateWithLifecycle()
    val isActivating by viewModel.isModelActivating.collectAsStateWithLifecycle()
    val activatingName by viewModel.activatingModelName.collectAsStateWithLifecycle()
    val activationStage by viewModel.activationStage.collectAsStateWithLifecycle()

    var cpuThreadsSlider by remember(activeCpuThreads) { mutableFloatStateOf(activeCpuThreads.toFloat()) }

    // Jalur Import: OpenDocument File Picker
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { pickedUri ->
            try {
                Toast.makeText(context, "Memulai penyalinan file model ke internal app...", Toast.LENGTH_SHORT).show()
                viewModel.importModel(pickedUri) { success, msg ->
                    try {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    } catch (toastEx: Exception) {
                        android.util.Log.e("ModelsScreen", "Toast error", toastEx)
                    }
                }
            } catch (ex: Exception) {
                android.util.Log.e("ModelsScreen", "Import launcher error", ex)
            }
        }
    }

    Scaffold(
        topBar = {
            NexoraHeader(
                title = "Model Manager & Catalog",
                activeModelName = activeModel.name,
                isOffline = isOffline,
                onBack = onBack
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
            // Hardware Telemetry & Acceleration Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkOutline.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Telemetri RAM Perangkat",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            StatusBadge(
                                text = if (deviceMem.isLowMemory) "LOW MEMORY" else "SUFFICIENT RAM",
                                color = if (deviceMem.isLowMemory) NexoraRose else NexoraEmerald
                            )
                        }

                        // RAM Bar
                        val usedRam = (deviceMem.totalRamMb - deviceMem.availableRamMb).coerceAtLeast(0)
                        val fraction = if (deviceMem.totalRamMb > 0) usedRam.toFloat() / deviceMem.totalRamMb.toFloat() else 0f
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Terpakai: $usedRam MB", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text("Tersedia: ${deviceMem.availableRamMb} MB / Total ${deviceMem.totalRamMb} MB", style = MaterialTheme.typography.labelSmall, color = NexoraCyan)
                            }
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = if (fraction > 0.85f) NexoraRose else NexoraCyan,
                                trackColor = DarkSurfaceVariant
                            )
                        }

                        HorizontalDivider(color = DarkOutline.copy(alpha = 0.4f))

                        // Real Hardware Accelerator Detection
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Diagnostik Chipset GPU",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = hardwareInfo.diagnosticSummary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    fontSize = 10.5.sp
                                )
                            }
                        }

                        // CPU Threads Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "CPU Inference Threads (${cpuThreadsSlider.toInt()})",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Slider(
                                value = cpuThreadsSlider,
                                onValueChange = {
                                    cpuThreadsSlider = it
                                    viewModel.setCpuThreads(it.toInt())
                                },
                                valueRange = 1f..8f,
                                steps = 6,
                                modifier = Modifier.width(150.dp),
                                colors = SliderDefaults.colors(thumbColor = NexoraCyan, activeTrackColor = NexoraCyan)
                            )
                        }

                        // Real GPU Hardware Acceleration Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Akselerasi Vulkan / Hardware",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                val statusText = if (isGpuActive) {
                                    "Akselerasi aktif via ${hardwareInfo.backendLabel}"
                                } else {
                                    "Akselerasi nonaktif (menggunakan CPU multi-threading)"
                                }
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isGpuActive) NexoraEmerald else TextMuted
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = isGpuActive,
                                onCheckedChange = { enable ->
                                    viewModel.setGpuEnabled(enable)
                                    val msg = if (enable) {
                                        if (hardwareInfo.hasVulkan) {
                                            "Akselerasi hardware Vulkan diaktifkan secara jujur."
                                        } else {
                                            "Akselerasi diaktifkan via ${hardwareInfo.backendLabel}."
                                        }
                                    } else {
                                        "Akselerasi hardware dimatikan, beralih ke komputasi CPU murni."
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = NexoraCyan),
                                modifier = Modifier.testTag("gpu_switch")
                            )
                        }
                    }
                }
            }

            // (a) JALUR IMPORT: Tombol Import File Model Lokal
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NexoraCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = "Import", tint = NexoraCyan, modifier = Modifier.size(20.dp))
                            Text(
                                "Jalur Import Model Lokal",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = "Punya file model di memori HP atau dibagikan orang lain? Salin byte utuh ke penyimpanan internal app dengan verifikasi bertahap.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 11.5.sp
                        )
                        Button(
                            onClick = {
                                importLauncher.launch(arrayOf("*/*"))
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NexoraCyan,
                                contentColor = DarkBackground
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("import_model_file_button")
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = "Pick file", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import File Model (.gguf / .litertlm / .task)", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // (b) JALUR DOWNLOAD: Katalog Model dari Berbagai Sumber
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Model dari Berbagai Sumber (${catalogModels.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    StatusBadge(text = "2 JALUR BERPROGRES", color = NexoraViolet)
                }
            }

            items(catalogModels) { entry ->
                CatalogModelCard(
                    entry = entry,
                    availableRam = deviceMem.availableRamMb,
                    onDownload = {
                        viewModel.downloadModel(entry.id) { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onActivate = {
                        viewModel.activateModel(entry.id) { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onUpdateUrl = { newUrl ->
                        viewModel.updateCustomModelUrl(entry.id, newUrl)
                        Toast.makeText(context, "URL model diperbarui", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    // Modal Spinner: "Memuat otak..." selama proses aktivasi model
    if (isActivating) {
        AlertDialog(
            onDismissRequest = { /* Modal tidak dapat ditutup sebelum selesai */ },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = NexoraCyan,
                        strokeWidth = 3.dp
                    )
                    Text("Memuat Arsitektur Model...", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (activationStage.isNotBlank()) activationStage else "Sedang memproses $activatingName...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NexoraCyan
                    )
                    Surface(
                        color = DarkSurfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Protokol Transparan Tanpa Status Palsu:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "1. Verifikasi berkas fisik di storage internal app\n2. Parsing metadata biner & evaluasi tensor\n3. Pemeriksaan batas alokasi RAM perangkat\n4. Unload model lama dari memori HP\n5. Uji probe respons mandiri sebelum dinyatakan siap",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun CatalogModelCard(
    entry: CatalogModelEntry,
    availableRam: Long,
    onDownload: () -> Unit,
    onActivate: () -> Unit,
    onUpdateUrl: (String) -> Unit
) {
    val canFitRam = entry.requiredRamMb <= availableRam || availableRam == 0L
    var customUrlInput by remember(entry.customDownloadUrl) { mutableStateOf(entry.customDownloadUrl) }
    var isEditingUrl by remember { mutableStateOf(false) }

    val isActive = entry.status == ModelInstallStatus.AKTIF
    val isInstalled = entry.status == ModelInstallStatus.TERPASANG
    val isInProgress = entry.status in listOf(
        ModelInstallStatus.MENGUNDUH,
        ModelInstallStatus.MENYALIN,
        ModelInstallStatus.MEMVERIFIKASI
    )

    val badgeColor = when (entry.status) {
        ModelInstallStatus.BELUM_TERPASANG -> TextMuted
        ModelInstallStatus.MENGUNDUH -> NexoraAmber
        ModelInstallStatus.MENYALIN -> NexoraAmber
        ModelInstallStatus.MEMVERIFIKASI -> NexoraCyan
        ModelInstallStatus.TERPASANG -> NexoraEmerald
        ModelInstallStatus.AKTIF -> NexoraCyan
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isActive) NexoraCyan else DarkOutline.copy(alpha = 0.5f),
                RoundedCornerShape(14.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header: Name, Format, Source & Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Sumber/Penulis: ${entry.sourceAuthor}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NexoraCyan
                    )
                    Text(
                        text = "Format: ${entry.format} • File: ${entry.filename}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusBadge(
                    text = entry.status.name,
                    color = badgeColor
                )
            }

            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            // Progress bar if downloading or copying or verifying
            if (isInProgress) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = entry.progressLabel.ifBlank { entry.status.name },
                            style = MaterialTheme.typography.labelSmall,
                            color = NexoraAmber,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${entry.progressPercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = NexoraAmber
                        )
                    }
                    LinearProgressIndicator(
                        progress = { entry.progressPercent.toFloat() / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = NexoraAmber,
                        trackColor = DarkSurface
                    )
                }
            }

            // Technical Specs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Ukuran Harapan", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text("${entry.sizeMb} MB", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                }
                Column {
                    Text("Konteks", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text("${entry.contextLength} Tok", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Est. RAM", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(
                        "${entry.requiredRamMb} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (canFitRam) NexoraEmerald else NexoraRose
                    )
                }
            }

            // Storage & Berkas Fisik On-Device (Jawaban untuk: "Trus itu ke save gak modelnya")
            Surface(
                color = DarkSurfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isInstalled || isActive) Icons.Default.SdStorage else Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = if (isInstalled || isActive) NexoraEmerald else TextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (isInstalled || isActive) "Tersimpan di Internal HP (Ke-Save)" else "Target Lokasi Berkas di HP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isInstalled || isActive) NexoraEmerald else TextSecondary
                            )
                        }
                        if (isInstalled || isActive) {
                            StatusBadge(text = "PERSISTENT", color = NexoraEmerald)
                        }
                    }
                    Text(
                        text = "Path: files/models/${entry.filename}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 10.5.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    if (isInstalled || isActive) {
                        Text(
                            text = "✓ Berkas biner tersimpan permanen di private storage HP (tidak terhapus saat app ditutup atau HP di-restart).",
                            style = MaterialTheme.typography.labelSmall,
                            color = NexoraCyan,
                            fontSize = 10.5.sp
                        )
                    }
                }
            }

            // Editable URL field
            Surface(
                color = DarkSurfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("URL Sumber Model (Dapat Diedit):", style = MaterialTheme.typography.labelSmall, color = NexoraCyan)
                        TextButton(
                            onClick = {
                                if (isEditingUrl) {
                                    onUpdateUrl(customUrlInput)
                                    isEditingUrl = false
                                } else {
                                    isEditingUrl = true
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(if (isEditingUrl) "Simpan" else "Ubah URL", fontSize = 11.sp, color = NexoraCyan)
                        }
                    }

                    if (isEditingUrl) {
                        OutlinedTextField(
                            value = customUrlInput,
                            onValueChange = { customUrlInput = it },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        Text(
                            text = customUrlInput.ifBlank { "Belum ada URL" },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    isActive -> {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            border = androidx.compose.foundation.BorderStroke(1.dp, NexoraCyan)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NexoraCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Sedang Aktif", color = NexoraCyan)
                        }
                    }
                    isInstalled -> {
                        Button(
                            onClick = onActivate,
                            enabled = canFitRam,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NexoraCyan,
                                contentColor = DarkBackground
                            )
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (canFitRam) "Aktifkan Model (Unload Lama & Probe)" else "RAM Kurang (${entry.requiredRamMb}MB Req)")
                        }
                    }
                    isInProgress -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            colors = ButtonDefaults.buttonColors(containerColor = NexoraAmber, contentColor = DarkBackground)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = DarkBackground)
                            Spacer(Modifier.width(6.dp))
                            Text(entry.status.name, fontSize = 12.sp)
                        }
                    }
                    else -> { // BELUM_TERPASANG
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NexoraViolet,
                                contentColor = TextPrimary
                            )
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Unduh Model (Resume HTTP Range)")
                        }
                    }
                }
            }
        }
    }
}
