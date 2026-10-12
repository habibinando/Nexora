package com.example.nexora.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: NexoraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val activeModel by viewModel.activeModelInfo.collectAsStateWithLifecycle()
    val isOffline by viewModel.offlineEnforced.collectAsStateWithLifecycle()
    val nonThinkingMode by viewModel.nonThinkingMode.collectAsStateWithLifecycle()
    val maxRetries by viewModel.maxRetries.collectAsStateWithLifecycle()
    val mathOracleResult by viewModel.mathOracleResult.collectAsStateWithLifecycle()
    val currentApiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val currentModel by viewModel.geminiModel.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var retrySliderVal by remember(maxRetries) { mutableFloatStateOf(maxRetries.toFloat()) }
    var inputApiKey by remember(currentApiKey) { mutableStateOf(currentApiKey) }
    var keyVisible by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            NexoraHeader(
                title = "Settings & Architecture",
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
            item {
                Text(
                    text = "Autonomy, Modes & Verification",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // 1. Math Oracle Self-Test Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NexoraEmerald.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(14.dp)
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Math Oracle Self-Test",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Verifikasi kejujuran deterministik: menguji perkalian acak dan mencatat eksekusi di Call Logs.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Button(
                                onClick = { viewModel.runMathOracleSelfTest() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NexoraEmerald,
                                    contentColor = DarkBackground
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("run_math_oracle_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Uji Oracle", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        mathOracleResult?.let { res ->
                            Surface(
                                color = DarkSurfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Ekspresi: ${res.expression}",
                                            fontWeight = FontWeight.Bold,
                                            color = NexoraCyan,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        StatusBadge(
                                            text = if (res.isValid) "VALID" else "INVALID",
                                            color = if (res.isValid) NexoraEmerald else NexoraRose
                                        )
                                    }
                                    Text(
                                        text = "Target Oracle: ${res.oracleExpected} | Hasil Kalkulasi: ${res.computedOutput}",
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "Waktu Eksekusi: ${res.latencyMs}ms • Tercatat di tab Call Logs",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Non-thinking Mode Toggle
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Non-thinking Mode",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    "Memaksa model berpikir cepat dan langsung memberikan jawaban akhir tanpa fase refleksi <think> panjang.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = nonThinkingMode,
                                onCheckedChange = { viewModel.setNonThinkingMode(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = NexoraCyan),
                                modifier = Modifier.testTag("non_thinking_switch")
                            )
                        }
                    }
                }
            }

            // 3. Strict Local-First Mode (Default ON)
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Strict Local-First Mode (Default ON)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    "Menjamin eksekusi 100% lokal di perangkat. Memblokir WebSearchTool dan request jaringan keluar.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = isOffline,
                                onCheckedChange = { viewModel.setOfflineEnforced(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = NexoraCyan),
                                modifier = Modifier.testTag("strict_local_first_switch")
                            )
                        }
                    }
                }
            }

            // 4. Arsitektur On-Device 100% Offline (Bebas Cloud & API Key)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NexoraEmerald.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(14.dp)
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
                                "Arsitektur On-Device 100% Offline",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            StatusBadge(
                                text = "100% On-Device",
                                color = NexoraEmerald
                            )
                        }
                        Text(
                            "Nexora beroperasi mandiri di perangkat tanpa dependensi cloud atau API key. Eksekusi alat (Python, kalkulator, berkas sandbox), basis data SQLite lokal, dan pelatihan bobot representasi berjalan langsung di CPU.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        OutlinedTextField(
                            value = inputApiKey,
                            onValueChange = { inputApiKey = it },
                            label = { Text("External API Key (Opsional / Tidak Wajib)") },
                            placeholder = { Text("Kosongkan untuk 100% On-Device") },
                            singleLine = true,
                            visualTransformation = if (keyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Key Visibility",
                                        tint = TextSecondary
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.setGeminiApiKey(inputApiKey)
                                    Toast.makeText(context, if (inputApiKey.isBlank()) "Mode 100% On-Device aktif" else "API Key disimpan!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NexoraCyan, contentColor = DarkBackground),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Simpan Preferensi")
                            }
                            if (currentApiKey.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        inputApiKey = ""
                                        viewModel.setGeminiApiKey("")
                                        Toast.makeText(context, "API Key dihapus (kembali ke 100% On-Device)", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NexoraRose),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, NexoraRose)
                                ) {
                                    Text("Reset")
                                }
                            }
                        }
                    }
                }
            }

            // 5. Retry Limits
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
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Batas Percobaan Ulang Eksperimen (Retry Limits)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            "Mencegah loop tak terhingga dengan membatasi iterasi modifikasi strategi sebelum meminta masukan manusia.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${retrySliderVal.toInt()} Percobaan", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = NexoraCyan)
                            Slider(
                                value = retrySliderVal,
                                onValueChange = {
                                    retrySliderVal = it
                                    viewModel.setMaxRetries(it.toInt())
                                },
                                valueRange = 1f..10f,
                                steps = 8,
                                modifier = Modifier.width(200.dp),
                                colors = SliderDefaults.colors(thumbColor = NexoraCyan, activeTrackColor = NexoraCyan)
                            )
                        }
                    }
                }
            }

            // 5. Architecture Info
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
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Tentang Arsitektur Nexora", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Versi Aplikasi: v${com.example.BuildConfig.VERSION_NAME} (Build ${com.example.BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall, color = NexoraEmerald, fontWeight = FontWeight.Bold)
                        Text("Nama Aplikasi: Nexora", style = MaterialTheme.typography.bodySmall, color = NexoraCyan)
                        Text("Package: com.nexora.ai", style = MaterialTheme.typography.bodySmall, color = NexoraViolet)
                        Text("Persona: Agen Lokal Otonom Serba Bisa (Matematika, Koding, Analisis, Menulis, Berkas, Keamanan)", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        Text("UX Thinking: Qwen Pattern dengan Thinking Bar (✦) & Skip Control", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text("Pengawasan: Human Oversight Dialog (APPROVE/DENY) pada alat berisiko tinggi", style = MaterialTheme.typography.bodySmall, color = NexoraAmber)
                    }
                }
            }

            // 6. Danger Zone
            item {
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NexoraRose),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NexoraRose)
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Hapus Memori Persisten & Riwayat Chat")
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Basis Data Lokal?", color = NexoraRose) },
            text = {
                Text("Tindakan ini akan menghapus seluruh memori kustom dan riwayat percakapan dari database SQLite lokal.", color = TextPrimary)
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.memoryEngine.clearAll()
                            viewModel.clearChat()
                            Toast.makeText(context, "Memori dan percakapan telah dibersihkan", Toast.LENGTH_SHORT).show()
                            showResetDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexoraRose)
                ) {
                    Text("Bersihkan Semua")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Batal", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}
