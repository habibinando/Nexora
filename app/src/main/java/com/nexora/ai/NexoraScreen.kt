package com.nexora.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun NexoraTheme(content: @Composable () -> Unit) {
    val scheme = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFF8AB4F8),
        onPrimary = Color(0xFF08111F),
        secondary = Color(0xFF9CC7D8),
        background = Color(0xFF0A101B),
        onBackground = Color(0xFFE7EDF6),
        surface = Color(0xFF111B2A),
        onSurface = Color(0xFFE7EDF6),
        surfaceVariant = Color(0xFF1B293A),
        onSurfaceVariant = Color(0xFFB5C3D5),
        error = Color(0xFFFF8A80)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
internal fun NexoraScreen(
    models: List<LocalModel>,
    activeModelId: String?,
    currentPage: AppPage,
    chatMode: ChatMode,
    messages: List<ChatMessage>,
    engineReady: Boolean,
    isBusy: Boolean,
    busyLabel: String,
    statusText: String,
    errorText: String?,
    systemInfo: String,
    hardwareInfo: String,
    onPageSelected: (AppPage) -> Unit,
    onModeSelected: (ChatMode) -> Unit,
    onImportModel: () -> Unit,
    onLoadModel: (LocalModel) -> Unit,
    onUnloadModel: () -> Unit,
    onRemoveModel: (LocalModel) -> Unit,
    onImportProjector: (LocalModel) -> Unit,
    visionReady: Boolean,
    visionImageName: String?,
    visionResult: String,
    isVisionImageSelected: Boolean,
    onPickVisionImage: () -> Unit,
    onAnalyzeVision: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onNewChat: () -> Unit,
    onDismissError: () -> Unit
) {
    Surface(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Header(engineReady, activeModelId?.let { id -> models.firstOrNull { it.id == id }?.name })
            Spacer(Modifier.height(14.dp))
            NavigationBar(currentPage, onPageSelected)
            Spacer(Modifier.height(14.dp))

            if (errorText != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2027)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(errorText, color = Color(0xFFFFDAD6), modifier = Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = onDismissError) { Text("Tutup") }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (currentPage) {
                    AppPage.CHAT -> ChatPage(messages, activeModelId, models, chatMode, isBusy, onModeSelected, onSendMessage, onNewChat)
                    AppPage.MODELS -> ModelsPage(
                        models, activeModelId, engineReady, isBusy, busyLabel,
                        onImportModel, onLoadModel, onUnloadModel, onRemoveModel, onImportProjector
                    )
                    AppPage.HARDWARE -> HardwarePage(hardwareInfo, systemInfo)
                    AppPage.VISION -> VisionPage(
                        visionReady, visionImageName, visionResult, isVisionImageSelected,
                        isBusy, onPickVisionImage, onAnalyzeVision
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (isBusy && busyLabel.isNotBlank()) {
                Text(busyLabel, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Header(engineReady: Boolean, activeModelName: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("NEXORA", color = MaterialTheme.colorScheme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("PRIVATE ON-DEVICE AI", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 1.5.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (engineReady) "ENGINE READY" else "ENGINE OFFLINE", color = if (engineReady) Color(0xFF8FE0B0) else Color(0xFFFFB4AB), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(activeModelName ?: "No model loaded", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NavigationBar(selected: AppPage, onSelect: (AppPage) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF121D2C), RoundedCornerShape(16.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        AppPage.entries.forEach { item ->
            val isSelected = item == selected
            TextButton(
                onClick = { onSelect(item) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (isSelected) Color(0xFF8AB4F8) else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(item.label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ChatPage(
    messages: List<ChatMessage>,
    activeModelId: String?,
    models: List<LocalModel>,
    chatMode: ChatMode,
    isBusy: Boolean,
    onModeSelected: (ChatMode) -> Unit,
    onSendMessage: (String) -> Unit,
    onNewChat: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatMode.entries.forEach { mode ->
                val selected = mode == chatMode
                Button(
                    onClick = { onModeSelected(mode) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(mode.label, fontSize = 12.sp) }
            }
            TextButton(onClick = onNewChat, enabled = !isBusy) { Text("Baru") }
        }
        Spacer(Modifier.height(12.dp))

        if (activeModelId == null) {
            EmptyState(
                "Model belum dimuat",
                if (models.isEmpty()) "Nexora tidak membawa model bawaan. Impor file GGUF milik Anda sendiri dari halaman Models."
                else "Pilih salah satu model di halaman Models, lalu tekan Muat ke perangkat."
            )
        } else if (messages.isEmpty()) {
            EmptyState(
                if (chatMode == ChatMode.CODING) "Siap membantu coding" else "Mulai percakapan",
                "Semua prompt dan inference diproses pada perangkat. Tidak ada API key atau layanan cloud."
            )
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                messages.forEach { MessageCard(it) }
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = activeModelId != null && !isBusy,
            placeholder = { Text("Tulis pesan…") },
            minLines = 2,
            maxLines = 5,
            shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { val text = input; input = ""; onSendMessage(text) },
            enabled = activeModelId != null && !isBusy && input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) { Text(if (isBusy) "Memproses…" else "Kirim") }
    }
}

@Composable
private fun MessageCard(message: ChatMessage) {
    val isUser = message.role == "user"
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isUser) Color(0xFF20344D) else MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(if (isUser) "ANDA" else "NEXORA", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(5.dp))
            Text(message.content.ifBlank { "…" }, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ModelsPage(
    models: List<LocalModel>,
    activeModelId: String?,
    engineReady: Boolean,
    isBusy: Boolean,
    busyLabel: String,
    onImportModel: () -> Unit,
    onLoadModel: (LocalModel) -> Unit,
    onUnloadModel: () -> Unit,
    onRemoveModel: (LocalModel) -> Unit,
    onImportProjector: (LocalModel) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("MODEL MANAGER", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Impor dan kelola model GGUF dari penyimpanan perangkat. Satu model aktif pada satu waktu.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onImportModel, enabled = engineReady && !isBusy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text("＋  Impor Model GGUF")
        }
        if (activeModelId != null) {
            TextButton(onClick = onUnloadModel, enabled = !isBusy) { Text("Lepas model aktif dari RAM") }
        }
        Spacer(Modifier.height(8.dp))

        if (models.isEmpty()) {
            EmptyState(
                "Belum ada model",
                "Pilih file .gguf dari memori internal atau kartu SD. Model tidak diunduh otomatis dan tidak ada model default."
            )
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                models.forEach { model ->
                    val active = model.id == activeModelId
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(model.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(formatBytes(model.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    Text(
                                        if (model.projectorPath != null) "Vision projector: ${model.projectorName ?: "terpasang"}"
                                        else "Vision projector: belum dipasang",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                }
                                if (active) Text("AKTIF", color = Color(0xFF8FE0B0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onLoadModel(model) }, enabled = engineReady && !isBusy && !active, modifier = Modifier.weight(1f)) {
                                    Text(if (active) "Dimuat" else "Muat")
                                }
                                TextButton(onClick = { onRemoveModel(model) }, enabled = !isBusy) { Text("Hapus") }
                            }
                            TextButton(onClick = { onImportProjector(model) }, enabled = engineReady && !isBusy) {
                                Text(if (model.projectorPath == null) "＋ Impor mmproj vision" else "Ganti mmproj vision")
                            }
                        }
                    }
                }
            }
        }
        if (isBusy && busyLabel.isNotBlank()) {
            Text(busyLabel, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HardwarePage(hardwareInfo: String, systemInfo: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PERANGKAT", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        InfoCard("Hardware & RAM", hardwareInfo)
        InfoCard("Informasi backend aktual", systemInfo)
        Text(
            "Build ini memakai backend CPU ARM64 yang dioptimalkan llama.cpp. Vulkan merupakan opsi build terpisah dan tidak diklaim aktif pada APK CPU.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun VisionPage(
    visionReady: Boolean,
    imageName: String?,
    result: String,
    imageSelected: Boolean,
    isBusy: Boolean,
    onPickImage: () -> Unit,
    onAnalyze: (String) -> Unit
) {
    var question by remember { mutableStateOf("Jelaskan isi gambar ini secara detail.") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("VISION", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (!visionReady) {
            EmptyState(
                "Vision belum siap",
                "Muat model multimodal yang kompatibel dan impor file mmproj pasangannya melalui Model Manager. Model teks biasa tidak dapat menganalisis gambar."
            )
        } else {
            Text(
                "Pemrosesan gambar berjalan di perangkat melalui encoder vision llama.cpp. File gambar tidak dikirim ke server.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Button(
                onClick = onPickImage,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (imageSelected) "Ganti Gambar" else "Pilih Gambar")
            }
            Text(
                imageName ?: "Belum ada gambar dipilih",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy,
                label = { Text("Pertanyaan tentang gambar") },
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(16.dp)
            )
            Button(
                onClick = { onAnalyze(question) },
                enabled = imageSelected && !isBusy && question.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (isBusy) "Menganalisis…" else "Analisis Gambar")
            }
            if (result.isNotBlank()) {
                InfoCard("Hasil analisis lokal", result)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun InfoCard(title: String, content: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(content, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
}
