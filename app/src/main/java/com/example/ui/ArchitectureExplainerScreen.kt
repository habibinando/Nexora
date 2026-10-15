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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AccentOrange
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

@Composable
fun ArchitectureExplainerScreen(
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyberBackground)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Honest Reality Header
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
                    imageVector = Icons.Default.Info,
                    contentDescription = "Info Icon",
                    tint = ElectricCyan,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Realita On-Device Training & NDK",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Transparan & ilmiah tanpa klaim palsu",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Section 1: Mobile Constraints vs Reality
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ReportProblem, contentDescription = "Batasan Fisik", tint = AccentOrange, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Mengapa HP Tidak Bisa Melatih LLM Raksasa (70B)?",
                        color = AccentOrange,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "1. Keterbatasan VRAM/RAM: Model 70B membutuhkan minimal 140 GB memori FP16 (atau 35 GB pada 4-bit) hanya untuk menyimpan bobot statis. Saat proses training, optimizer Adam dan aktivasi gradien membutuhkan tambahan memori 3x lipat (~400 GB). RAM smartphone (6-16 GB) akan langsung dihentikan oleh Android LMK (Low Memory Killer).\n\n" +
                            "2. Termal & Baterai: Melatih miliaran parameter menghasilkan beban termal ekstrem yang memicu thermal throttling hingga frekuensi CPU/GPU turun drastis demi mencegah kerusakan baterai lithium.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        // Section 2: What is feasible
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Code, contentDescription = "Solusi Nyata", tint = NeuralGreen, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Solusi Nyata: Apa yang Optimal di HP?",
                        color = NeuralGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Aplikasi ini mengimplementasikan apa yang benar-benar bisa bekerja dengan performa tinggi:\n\n" +
                            "• On-Device Recurrent Neural Networks (Char-RNN) dengan komputasi backpropagation through time (BPTT) nyata.\n" +
                            "• Fine-tuning adapter berukuran kecil (LoRA/Adapter) yang dapat disimpan sebagai file checkpoint biner ringan.\n" +
                            "• Eksekusi Native C++20 via NDK 27 LTS dengan instruksi vektor ARM NEON SIMD tanpa overhead JVM.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        // Section 3: DL4J vs Native NDK
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Analisis Deeplearning4j vs C++ Native NDK",
                    color = NeonPurple,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Deeplearning4j (DL4J) dirancang primer untuk ekosistem Enterprise Java/Scala di Server. Saat dimasukkan ke APK Android, dependensi ND4J dan ByteBuddy sering menyebabkan konflik duplikasi kelas DEX, ukuran APK membengkak ratusan megabyte, dan lambat karena Garbage Collection.\n\n" +
                            "Sebagai gantinya, aplikasi ini menggunakan Native C++20 NDK 27 LTS terkompilasi langsung melalui CMake dengan flag -O3 dan NEON vectorization. Menghasilkan eksekusi tercepat tanpa dependensi bloated.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        // Section 4: CI/CD GitHub Actions Build
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, contentDescription = "Terminal", tint = ElectricCyan, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Cara Build APK di GitHub Actions",
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Sesuai permintaan Anda, seluruh konfigurasi CI/CD sudah siap di .github/workflows/build-apk.yml. Anda cukup menghubungkan repositori GitHub:",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CyberSurfaceVariant, RoundedCornerShape(6.dp))
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "git remote add origin https://github.com/USER/REPO.git\ngit branch -M main\ngit push -u origin main",
                        color = ElectricCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "GitHub Actions runner akan meng-compile C++ NDK dan Kotlin dengan JDK 21, lalu file APK siap diunduh langsung dari menu 'Actions > Artifacts'.",
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
        }
    }
}
