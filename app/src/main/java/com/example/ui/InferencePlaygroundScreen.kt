package com.example.ui

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
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.neural.LocalNeuralTrainer
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceCard
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeuralGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.util.Locale

@Composable
fun InferencePlaygroundScreen(
    trainer: LocalNeuralTrainer,
    modifier: Modifier = Modifier
) {
    var promptInput by remember { mutableStateOf("Neural") }
    var generationLength by remember { mutableIntStateOf(60) }
    var temperature by remember { mutableFloatStateOf(0.7f) }

    var generatedOutput by remember { mutableStateOf<String?>(null) }
    var inferenceLatencyMs by remember { mutableFloatStateOf(-1f) }
    var isGenerating by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyberBackground)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = "Inference Icon",
                    tint = ElectricCyan,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Playground Inferensi On-Device",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Generasi teks murni menggunakan bobot memori lokal",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Prompt Input Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Prompt Awal (Seed Text)",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Karakter awal untuk menginisialisasi hidden state model",
                    color = TextTertiary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inference_prompt_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = BorderSubtle,
                        focusedContainerColor = CyberSurfaceVariant,
                        unfocusedContainerColor = CyberSurfaceVariant
                    ),
                    placeholder = { Text("Ketik kata awal (misal: 'Kecerdasan', 'Neural')...", color = TextTertiary) }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Length Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Jumlah Karakter yang Dihasilkan:", color = TextSecondary, fontSize = 13.sp)
                    Text("$generationLength karakter", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = generationLength.toFloat(),
                    onValueChange = { generationLength = it.toInt() },
                    valueRange = 10f..200f,
                    colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan),
                    modifier = Modifier.testTag("generation_length_slider")
                )

                // Temperature Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Temperature (Kreativitas Sampling):", color = TextSecondary, fontSize = 13.sp)
                    Text(String.format(Locale.US, "%.2f", temperature), color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0.2f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = NeonPurple, activeTrackColor = NeonPurple),
                    modifier = Modifier.testTag("temperature_slider")
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        val model = trainer.activeModel
                        if (model != null) {
                            isGenerating = true
                            val start = System.nanoTime()
                            val output = model.generate(
                                seedText = promptInput.ifBlank { "A" },
                                length = generationLength,
                                temperature = temperature
                            )
                            val elapsed = (System.nanoTime() - start) / 1_000_000f
                            inferenceLatencyMs = elapsed
                            generatedOutput = output
                            isGenerating = false
                        } else {
                            generatedOutput = "Model belum diinisialisasi atau dilatih. Silakan buka tab 'Training' dan tekan 'Mulai Training' terlebih dahulu."
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("run_inference_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = "Generate", modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Generate On-Device", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Generated Output Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Hasil Generasi Neural",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (inferenceLatencyMs > 0) {
                        Text(
                            text = String.format(Locale.US, "Latensi: %.2f ms", inferenceLatencyMs),
                            color = NeuralGreen,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CyberSurfaceVariant, RoundedCornerShape(8.dp))
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = generatedOutput ?: "Hasil inferensi akan muncul di sini setelah menekan 'Generate On-Device'.",
                        color = if (generatedOutput != null) TextPrimary else TextTertiary,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp
                    )
                }
            }
        }

        // Real Explanation of Model Training
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = "Perbandingan", tint = NeonPurple, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Dinamika Pelatihan Bobot", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Sebelum model dilatih (loss ~ 4.0), generasi karakter bersifat stokastik acak. Seiring bertambahnya epoch dan penurunan nilai loss di NDK, matriks Whh dan Why mempelajari transisi n-gram dari dataset pelatihan sehingga keluaran kalimat menjadi makin terstruktur.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
