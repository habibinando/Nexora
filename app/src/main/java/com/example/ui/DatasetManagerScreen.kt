package com.example.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceCard
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeuralGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DatasetManagerScreen(
    trainer: LocalNeuralTrainer,
    onDatasetChanged: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val activeDataset by DatasetRepository.activeDataset.collectAsState()

    var customInputText by remember { mutableStateOf(activeDataset.text) }
    var selectedPreset by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // SAF File Picker for .txt, .json, .csv, .md
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val fileName = queryFileName(context, uri)
                val result = DatasetRepository.importFromFileUri(context, uri, fileName)
                if (result.isSuccess) {
                    val dataset = result.getOrThrow()
                    customInputText = dataset.text
                    trainer.createCustomModel(datasetText = dataset.text)
                    statusMessage = "Berhasil mengimpor: ${dataset.name} (${dataset.characterCount} karakter, ${dataset.uniqueVocabSize} vocab unik)"
                    onDatasetChanged()
                } else {
                    statusMessage = "Gagal membaca file: ${result.exceptionOrNull()?.message}"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Dataset, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Manajemen & Impor Dataset", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text(
            "Impor file teks mentah Anda sendiri atau gunakan korpus bawaan untuk melatih model Nexora secara on-device.",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        // Status Feedback
        statusMessage?.let { msg ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                border = ButtonDefaults.outlinedButtonBorder
            ) {
                Text(
                    text = msg,
                    color = NeuralGreen,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Action Buttons: Import File & Preset Selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Pilih Sumber Dataset", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            filePickerLauncher.launch("text/*")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("import_file_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Impor File .txt/.json", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Atau pilih Korpus Kurasi:", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf("Percakapan", "Teori AI", "Kode Kotlin")
                    presets.forEachIndexed { index, label ->
                        FilterChip(
                            selected = selectedPreset == index && !activeDataset.isCustomOrImported,
                            onClick = {
                                selectedPreset = index
                                DatasetRepository.selectPresetDataset(index)
                                customInputText = DatasetRepository.activeDataset.value.text
                                trainer.createCustomModel(datasetText = customInputText)
                                statusMessage = "Mengaktifkan korpus: $label"
                                onDatasetChanged()
                            },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan,
                                selectedLabelColor = Color.Black,
                                containerColor = CyberSurface
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Real Analytics Metrics Cards
        Text("Metrik Dataset Aktual", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                title = "Total Karakter",
                value = activeDataset.characterCount.toString(),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Total Kata",
                value = activeDataset.wordCount.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                title = "Ukuran Data",
                value = formatBytes(activeDataset.sizeBytes),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Ukuran Vocab (V)",
                value = "${activeDataset.uniqueVocabSize} token unik",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Custom Text Dataset Editor
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Editor Teks Dataset", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("${customInputText.length} karakter", color = TextSecondary, fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = customInputText,
                    onValueChange = { customInputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .testTag("dataset_editor_field"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CyberSurface,
                        unfocusedContainerColor = CyberSurface,
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = CyberSurface,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    placeholder = { Text("Tulis atau tempel teks dataset di sini...", color = TextTertiary) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        DatasetRepository.setCustomTextDataset("Teks Editor", customInputText)
                        trainer.createCustomModel(datasetText = customInputText)
                        statusMessage = "Dataset kustom berhasil diterapkan ke model aktif."
                        onDatasetChanged()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("apply_dataset_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Terapkan ke Model Pelatihan", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Vocabulary & Tokenizer Frequency Inspector
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Distribusi Frekuensi Token (Top 25)", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Karakter paling sering muncul yang dipelajari matriks embedding Wxh:", color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    activeDataset.charFrequency.take(25).forEach { (char, count) ->
                        val displayChar = when (char) {
                            ' ' -> "spasi"
                            '\n' -> "\\n"
                            '\t' -> "\\t"
                            else -> char.toString()
                        }
                        Box(
                            modifier = Modifier
                                .background(CyberSurface, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(displayChar, color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("$count×", color = TextTertiary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, color = TextSecondary, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun queryFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
    } catch (_: Exception) {}
    return name
}
