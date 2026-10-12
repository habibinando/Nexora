package com.example.nexora.ui.screens.training

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nexora.engine.peft.PeftConfigManager
import com.example.nexora.engine.peft.PeftLoraConfig
import com.example.nexora.model.database.AdapterModuleEntity
import com.example.nexora.ui.components.StatusBadge
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.ui.theme.*

@Composable
fun PeftLoraTab(
    viewModel: NexoraViewModel,
    adapters: List<AdapterModuleEntity>,
    vocabSize: Int,
    hiddenDim: Int,
    embedDim: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var peftRank by remember { mutableIntStateOf(8) }
    var peftAlpha by remember { mutableIntStateOf(16) }
    var peftDropout by remember { mutableFloatStateOf(0.05f) }
    var selectedModules by remember { mutableStateOf(setOf("w_h", "w_out")) }
    var showConfigJsonDialog by remember { mutableStateOf(false) }

    val currentConfig = remember(peftRank, peftAlpha, peftDropout, selectedModules) {
        PeftLoraConfig(
            peftType = "LORA",
            taskType = "CAUSAL_LM",
            r = peftRank,
            loraAlpha = peftAlpha,
            loraDropout = peftDropout,
            targetModules = selectedModules.toList(),
            baseModelNameOrPath = "Nexora-Local-Neural"
        )
    }

    // Kalkulasi Parameter Nyata (Strict Real Math - No Mock / No Hardcode)
    val trainableParams = remember(currentConfig, vocabSize, hiddenDim, embedDim) {
        currentConfig.computeTrainableParams(vocabSize = vocabSize, hiddenDim = hiddenDim, embedDim = embedDim)
    }
    val totalBaseParams = remember(currentConfig, vocabSize, hiddenDim, embedDim) {
        currentConfig.computeBaseModelParams(vocabSize = vocabSize, hiddenDim = hiddenDim, embedDim = embedDim)
    }
    val trainablePercent = remember(trainableParams, totalBaseParams) {
        if (totalBaseParams > 0) (trainableParams.toDouble() / totalBaseParams.toDouble()) * 100.0 else 0.0
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Banner Penjelasan Standar PEFT (Hugging Face)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NexoraViolet.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Extension, contentDescription = null, tint = NexoraViolet, modifier = Modifier.size(22.dp))
                        Column {
                            Text(
                                text = "Konfigurasi PEFT / LoRA (Library peft)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Standar Hugging Face Parameter-Efficient Fine-Tuning",
                                style = MaterialTheme.typography.labelSmall,
                                color = NexoraViolet
                            )
                        }
                    }
                    Text(
                        text = "LoRA (Low-Rank Adaptation) membekukan (freeze) matriks model dasar dan menyisipkan pasangan matriks rank rendah A & B yang dapat dilatih. Ini menghemat memori hingga 99% dan memungkinkan pelatihan efisien di CPU ponsel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Kalkulasi Parameter Nyata (Real Formula)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NexoraCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Kalkulasi Parameter & Efisiensi Nyata:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Surface(
                        color = DarkSurfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Formula LoRA:", fontSize = 11.sp, color = TextMuted)
                                Text("ΔW = B × A (Rank r = $peftRank)", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = NexoraCyan)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Parameter Trainable:", fontSize = 11.sp, color = TextMuted)
                                Text("%,d params".format(trainableParams), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = NexoraEmerald)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Total Parameter Base Model:", fontSize = 11.sp, color = TextMuted)
                                Text("%,d params".format(totalBaseParams), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Rasio Parameter Trainable:", fontSize = 11.sp, color = TextMuted)
                                Text("%.2f%% dari base model".format(trainablePercent), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = NexoraViolet)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Ukuran File Adapter di Disk:", fontSize = 11.sp, color = TextMuted)
                                val sizeBytes = PeftConfigManager.getAdapterSizeBytes(context, "inc_lora")
                                    ?: PeftConfigManager.getAdapterSizeBytes(context, "local_char_rnn_weights")
                                Text(
                                    text = sizeBytes?.let { "%,d bytes (%.1f KB)".format(it, it / 1024f) } ?: "belum ada",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (sizeBytes != null) NexoraEmerald else NexoraAmber
                                )
                            }
                        }
                    }
                }
            }
        }

        // Panel Konfigurasi Hyperparameter LoRA
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkOutline.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Hyperparameter LoRA (peft.LoraConfig):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    // 1. LoRA Rank (r)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("LoRA Rank (r): $peftRank", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NexoraCyan)
                            Text("Kapasitas rank matriks dekomposisi", fontSize = 11.sp, color = TextMuted)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(4, 8, 16, 32).forEach { rVal ->
                                FilterChip(
                                    selected = peftRank == rVal,
                                    onClick = { peftRank = rVal },
                                    label = { Text("r = $rVal", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = NexoraCyan,
                                        selectedLabelColor = DarkBackground
                                    )
                                )
                            }
                        }
                    }

                    // 2. LoRA Alpha (alpha)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("LoRA Alpha (α): $peftAlpha", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NexoraEmerald)
                            Text("Faktor skala gradien (Scaling = α / r = ${peftAlpha / peftRank.toFloat()})", fontSize = 11.sp, color = TextMuted)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(8, 16, 32, 64).forEach { aVal ->
                                FilterChip(
                                    selected = peftAlpha == aVal,
                                    onClick = { peftAlpha = aVal },
                                    label = { Text("α = $aVal", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = NexoraEmerald,
                                        selectedLabelColor = DarkBackground
                                    )
                                )
                            }
                        }
                    }

                    // 3. Target Modules
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Target Modules (Lapisan yang Diadaptasi):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("w_h" to "Recurrent (w_h)", "w_out" to "Output Proj (w_out)").forEach { (modKey, label) ->
                                val isChecked = selectedModules.contains(modKey)
                                FilterChip(
                                    selected = isChecked,
                                    onClick = {
                                        selectedModules = if (isChecked) {
                                            if (selectedModules.size > 1) selectedModules - modKey else selectedModules
                                        } else {
                                            selectedModules + modKey
                                        }
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = NexoraViolet,
                                        selectedLabelColor = DarkBackground
                                    )
                                )
                            }
                        }
                    }

                    // 4. Tombol Aksi PEFT
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showConfigJsonDialog = true },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NexoraCyan),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NexoraCyan)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Lihat adapter_config.json", fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                viewModel.setPeftConfig(currentConfig)
                                val folder = PeftConfigManager.savePeftAdapter(context, "peft_lora_active", currentConfig)
                                Toast.makeText(context, "✓ Konfigurasi PEFT tersimpan di ${folder.name}/adapter_config.json", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = NexoraEmerald, contentColor = DarkBackground)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Terapkan PEFT", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Daftar Modul Adapter yang Terpasang
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Adapter Modules Tersimpan (${adapters.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                StatusBadge(
                    text = if (adapters.any { it.isEnabled }) "AKTIF" else "NONAKTIF",
                    color = if (adapters.any { it.isEnabled }) NexoraEmerald else TextMuted
                )
            }
        }

        if (adapters.isEmpty()) {
            item {
                Surface(
                    color = DarkSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = TextMuted, modifier = Modifier.size(32.dp))
                        Text("Belum Ada Adapter Tersimpan", fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            text = "Jalankan pelatihan on-device di tab Studio untuk menghasilkan bobot adapter .safetensors pertama Anda.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(adapters) { adapter: AdapterModuleEntity ->
                AdapterCard(
                    adapter = adapter,
                    onToggle = { isEnabled: Boolean -> viewModel.toggleAdapter(adapter.moduleId, isEnabled) }
                )
            }
        }
    }

    // Dialog Pratinjau adapter_config.json Standar Hugging Face PEFT
    if (showConfigJsonDialog) {
        val jsonText = currentConfig.toAdapterConfigJson()
        AlertDialog(
            onDismissRequest = { showConfigJsonDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = NexoraCyan)
                    Text("adapter_config.json (Format PEFT)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Format standar library peft Hugging Face untuk diunggah ke Hugging Face Hub:",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Surface(
                        color = DarkSurfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                    ) {
                        Text(
                            text = jsonText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = NexoraCyan,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("adapter_config.json", jsonText))
                        Toast.makeText(context, "✓ Disalin ke Clipboard", Toast.LENGTH_SHORT).show()
                        showConfigJsonDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexoraCyan, contentColor = DarkBackground)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Salin JSON", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigJsonDialog = false }) {
                    Text("Tutup", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}
