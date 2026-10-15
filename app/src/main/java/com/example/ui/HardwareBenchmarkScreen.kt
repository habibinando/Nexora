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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.neural.HardwareBenchmarkEngine
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
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun HardwareBenchmarkScreen(
    benchmarkEngine: HardwareBenchmarkEngine,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val spec = remember { benchmarkEngine.getDeviceSpec() }
    val benchmarkResult by benchmarkEngine.latestBenchmark.collectAsState()
    val isBenchmarking by benchmarkEngine.isBenchmarking.collectAsState()

    var matrixDimension by remember { mutableIntStateOf(128) }
    var iterationCount by remember { mutableIntStateOf(10) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyberBackground)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
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
                    imageVector = Icons.Default.Speed,
                    contentDescription = "Benchmark Icon",
                    tint = ElectricCyan,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Benchmark Hardware & Tensor NDK",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Pengukuran kecepatan FLOPs nyata pada SoC perangkat",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Hardware Specification Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Spesifikasi Hardware Aktual",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Dideteksi secara langsung dari Android Runtime",
                    color = TextTertiary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                SpecRow("Perangkat", spec.deviceModel)
                SpecRow("Sistem Operasi", spec.androidVersion)
                SpecRow("Jumlah Core CPU", "${spec.cpuCores} Threads Aktif")
                SpecRow("Primary ABI", spec.primaryAbi)
                SpecRow("Max Heap Memory", "${spec.maxHeapMemoryMb} MB")
                SpecRow("Total Memory JVM", "${spec.totalMemoryMb} MB")
                SpecRow("Engine Akselerasi", spec.ndkInfo)
            }
        }

        // Benchmark Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Konfigurasi Uji Matriks Tensor",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Operasi perkalian matriks densitas tinggi (C = A x B)",
                    color = TextTertiary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Dimensi Matriks ($matrixDimension x $matrixDimension):", color = TextSecondary, fontSize = 13.sp)
                    Text("${matrixDimension * matrixDimension} elemen", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = matrixDimension.toFloat(),
                    onValueChange = { matrixDimension = (it.toInt() / 32) * 32 },
                    valueRange = 64f..256f,
                    steps = 5,
                    enabled = !isBenchmarking,
                    colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan),
                    modifier = Modifier.testTag("benchmark_dimension_slider")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Jumlah Iterasi:", color = TextSecondary, fontSize = 13.sp)
                    Text("$iterationCount iterasi", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = iterationCount.toFloat(),
                    onValueChange = { iterationCount = it.toInt() },
                    valueRange = 5f..30f,
                    steps = 24,
                    enabled = !isBenchmarking,
                    colors = SliderDefaults.colors(thumbColor = NeonPurple, activeTrackColor = NeonPurple),
                    modifier = Modifier.testTag("benchmark_iterations_slider")
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            benchmarkEngine.runBenchmark(
                                matrixDimension = matrixDimension,
                                iterations = iterationCount
                            )
                        }
                    },
                    enabled = !isBenchmarking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("run_benchmark_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black)
                ) {
                    if (isBenchmarking) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Menjalankan Komputasi...")
                    } else {
                        Icon(Icons.Default.Bolt, contentDescription = "Benchmark", modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Jalankan Benchmark Nyata", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Real Benchmark Results (Strictly adhering to: display "belum ada" if empty)
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
                        text = "Hasil Benchmark Nyata",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (benchmarkResult != null) {
                        OutlinedButton(
                            onClick = { benchmarkEngine.clearBenchmark() },
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("clear_benchmark_button")
                        ) {
                            Text("Reset", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricTile(
                        label = "Durasi Eksekusi",
                        value = benchmarkResult?.let { String.format(Locale.US, "%.2f ms", it.durationMs) } ?: "belum ada",
                        accentColor = NeuralGreen,
                        modifier = Modifier.weight(1f)
                    )

                    MetricTile(
                        label = "Throughput Komputasi",
                        value = benchmarkResult?.let { String.format(Locale.US, "%.2f GFLOPS", it.gflops) } ?: "belum ada",
                        accentColor = ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CyberSurfaceVariant, RoundedCornerShape(8.dp))
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text("Engine yang Mengeksekusi:", color = TextTertiary, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = benchmarkResult?.hardwareInfo ?: "belum ada",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextTertiary, fontSize = 12.sp)
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}
