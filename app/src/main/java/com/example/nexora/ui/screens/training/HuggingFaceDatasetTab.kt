package com.example.nexora.ui.screens.training

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.example.nexora.engine.dataset.HuggingFaceDatasetClient
import com.example.nexora.engine.dataset.HuggingFaceFetchResult
import com.example.nexora.engine.dataset.HuggingFaceSample
import com.example.nexora.ui.components.StatusBadge
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.ui.theme.*

@Composable
fun HuggingFaceDatasetTab(
    viewModel: NexoraViewModel,
    hfResult: HuggingFaceFetchResult?,
    isFetching: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var datasetIdInput by remember { mutableStateOf("tatsu-lab/alpaca") }
    var selectedSplit by remember { mutableStateOf("train") }
    var sampleLimit by remember { mutableIntStateOf(10) }
    var isImportingToLocal by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Banner Penjelasan Hugging Face Hub On-Device
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NexoraAmber.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = NexoraAmber, modifier = Modifier.size(22.dp))
                        Column {
                            Text(
                                text = "Hugging Face Datasets Hub",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Ambil dataset publik langsung dari server Hugging Face",
                                style = MaterialTheme.typography.labelSmall,
                                color = NexoraAmber
                            )
                        }
                    }
                    Text(
                        text = "Aplikasi ini terhubung langsung ke Hugging Face Datasets API (datasets-server.huggingface.co). Anda dapat mengunduh sampel teks asli dari ribuan repositori Hugging Face dan langsung mengimpornya ke database SQLite lokal HP untuk dilatihkan ke adapter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Pilihan Dataset Populer (Presets)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkOutline.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Pilih Dataset Populer Hugging Face:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HuggingFaceDatasetClient.POPULAR_DATASETS.forEach { meta ->
                            val isSelected = datasetIdInput.equals(meta.id, ignoreCase = true)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    datasetIdInput = meta.id
                                    selectedSplit = meta.defaultSplit
                                },
                                label = { Text(meta.id, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NexoraAmber,
                                    selectedLabelColor = DarkBackground
                                )
                            )
                        }
                    }

                    // Tampilkan deskripsi singkat preset terpilih jika cocok
                    HuggingFaceDatasetClient.POPULAR_DATASETS.firstOrNull { it.id.equals(datasetIdInput, ignoreCase = true) }?.let { curMeta ->
                        Text(
                            text = "ℹ️ ${curMeta.description}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NexoraCyan
                        )
                    }

                    HorizontalDivider(color = DarkOutline.copy(alpha = 0.3f), thickness = 1.dp)

                    // Input Repo ID Kustom
                    OutlinedTextField(
                        value = datasetIdInput,
                        onValueChange = { datasetIdInput = it },
                        label = { Text("Dataset ID di Hugging Face (owner/dataset)") },
                        placeholder = { Text("misal: tatsu-lab/alpaca atau roneneldan/TinyStories") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NexoraAmber,
                            unfocusedBorderColor = DarkOutline
                        ),
                        singleLine = true
                    )

                    // Pilihan Jumlah Sampel (5, 10, 20, 50)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Jumlah Baris:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(5, 10, 20, 50).forEach { limit ->
                                FilterChip(
                                    selected = sampleLimit == limit,
                                    onClick = { sampleLimit = limit },
                                    label = { Text("$limit baris", fontSize = 10.5.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = NexoraCyan,
                                        selectedLabelColor = DarkBackground
                                    )
                                )
                            }
                        }
                    }

                    // Tombol Unduh dari Hugging Face
                    Button(
                        onClick = {
                            if (datasetIdInput.isNotBlank()) {
                                viewModel.fetchHuggingFaceDataset(
                                    datasetId = datasetIdInput,
                                    split = selectedSplit,
                                    limit = sampleLimit
                                )
                            } else {
                                Toast.makeText(context, "Masukkan ID dataset terlebih dahulu", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NexoraAmber, contentColor = DarkBackground),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isFetching && datasetIdInput.isNotBlank()
                    ) {
                        if (isFetching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = DarkBackground, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Mengunduh dari Hugging Face API...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Unduh dari Hugging Face", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Tampilkan Hasil Unduhan
        if (hfResult != null) {
            if (hfResult.isSuccess) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, NexoraEmerald.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NexoraEmerald, modifier = Modifier.size(20.dp))
                                    Text("Berhasil Diambil dari Hugging Face", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                }
                                StatusBadge(text = "${hfResult.totalFetched} SAMPEL", color = NexoraEmerald)
                            }

                            Text(
                                text = "Dataset: ${hfResult.datasetId} • Siap diimpor ke basis data Room SQLite lokal",
                                fontSize = 11.5.sp,
                                color = TextSecondary
                            )

                            Button(
                                onClick = {
                                    isImportingToLocal = true
                                    viewModel.importHuggingFaceSamplesToDataset(hfResult.samples) { count ->
                                        isImportingToLocal = false
                                        Toast.makeText(context, "✓ Berhasil mengimpor $count sampel dari Hugging Face ke Dataset Lokal!", Toast.LENGTH_LONG).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NexoraEmerald, contentColor = DarkBackground),
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isImportingToLocal && hfResult.samples.isNotEmpty()
                            ) {
                                Icon(Icons.Default.LibraryAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Impor Semua ke Dataset Pelatihan Lokal", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Header Pratinjau Sampel
                item {
                    Text(
                        text = "Pratinjau Sampel Baris (${hfResult.samples.size}):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                items(hfResult.samples) { sample ->
                    HuggingFaceSampleCard(sample = sample)
                }
            } else {
                // Tampilan Error
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, NexoraRose.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = NexoraRose, modifier = Modifier.size(20.dp))
                                Text("Gagal Mengambil Dataset", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = NexoraRose)
                            }
                            Text(
                                text = hfResult.errorMessage,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HuggingFaceSampleCard(sample: HuggingFaceSample) {
    Surface(
        color = DarkSurfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Baris #${sample.rowId} (${sample.category})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = NexoraCyan
                )
                Text(
                    text = "${sample.prompt.length + sample.completion.length} karakter",
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }

            Text("Prompt / Masukan:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Surface(
                color = DarkBackground.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = sample.prompt.take(240),
                    fontSize = 11.sp,
                    color = TextPrimary,
                    modifier = Modifier.padding(6.dp)
                )
            }

            Text("Target Jawaban / Solusi:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = NexoraEmerald)
            Surface(
                color = DarkBackground.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = sample.completion.take(240),
                    fontSize = 11.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(6.dp)
                )
            }
        }
    }
}
