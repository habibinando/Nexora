package com.example.nexora.ui.screens.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nexora.model.database.ChatMessageEntity
import com.example.nexora.ui.components.NexoraHeader
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.nexora.util.DocumentTextExtractor
import com.example.ui.theme.*
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ChatAttachmentType {
    IMAGE, DOCUMENT
}

data class ChatAttachment(
    val name: String,
    val type: ChatAttachmentType,
    val uriString: String,
    val contentText: String? = null
)

@Composable
fun ChatScreen(
    viewModel: NexoraViewModel,
    onBack: () -> Unit,
    onNavigateToExperiments: () -> Unit,
    onNavigateToSettings: (() -> Unit)? = null,
    onNavigateToPyTorch: (() -> Unit)? = null,
    onNavigateToDataset: (() -> Unit)? = null,
    onOpenMenu: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var showOptionsMenu by remember { mutableStateOf(false) }

    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val streamingState by viewModel.streamingState.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val activeModel by viewModel.activeModelInfo.collectAsStateWithLifecycle()
    val isOffline by viewModel.offlineEnforced.collectAsStateWithLifecycle()
    val pendingOversight by viewModel.pendingHumanOversight.collectAsStateWithLifecycle()
    val apiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val trainingData by viewModel.trainingData.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val coroutineScope = rememberCoroutineScope()
    var isExtractingAttachmentText by remember { mutableStateOf(false) }

    // Attachment & Lifecycle States
    var attachedItem by remember { mutableStateOf<ChatAttachment?>(null) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showLifecycleDialog by remember { mutableStateOf(false) }
    var isLifecycleRunning by remember { mutableStateOf(false) }
    var lifecycleStepText by remember { mutableStateOf("") }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "foto_${System.currentTimeMillis()}.jpg"
            isExtractingAttachmentText = true
            Toast.makeText(context, "🔍 Membaca teks foto dengan ML Kit OCR...", Toast.LENGTH_SHORT).show()
            coroutineScope.launch {
                val res = DocumentTextExtractor.extractTextFromUri(context, uri, fileName)
                isExtractingAttachmentText = false
                attachedItem = ChatAttachment(
                    name = fileName,
                    type = ChatAttachmentType.IMAGE,
                    uriString = uri.toString(),
                    contentText = if (res.isSuccess) res.extractedText else null
                )
                if (res.isSuccess) {
                    Toast.makeText(context, "✓ Teks foto terbaca (${res.characterCount} karakter)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Foto terpilih: ${res.notes}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "dokumen.pdf"
            isExtractingAttachmentText = true
            Toast.makeText(context, "📄 Mengekstrak teks dokumen/PDF...", Toast.LENGTH_SHORT).show()
            coroutineScope.launch {
                val res = DocumentTextExtractor.extractTextFromUri(context, uri, fileName)
                isExtractingAttachmentText = false
                attachedItem = ChatAttachment(
                    name = fileName,
                    type = ChatAttachmentType.DOCUMENT,
                    uriString = uri.toString(),
                    contentText = if (res.isSuccess) res.extractedText else null
                )
                if (res.isSuccess) {
                    Toast.makeText(context, "✓ Teks dokumen/PDF terbaca (${res.characterCount} karakter)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Berkas dipilih: ${res.notes}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Smooth auto-scroll to latest message
    LaunchedEffect(messages.size, streamingState.rawText) {
        if (messages.isNotEmpty() || isGenerating) {
            val target = if (isGenerating) messages.size else (messages.size - 1).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .imePadding()
    ) {
        // Top Header with Drawer Hamburger (baris 3) & 3-dots Overflow Menu
        NexoraHeader(
            title = "Nexora Chat",
            activeModelName = activeModel.name,
            isOffline = isOffline,
            onBack = if (onOpenMenu != null) null else onBack,
            onMenuClick = onOpenMenu,
            actions = {
                IconButton(
                    onClick = { viewModel.clearChat() },
                    modifier = Modifier.testTag("clear_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Hapus Riwayat Chat",
                        tint = TextSecondary
                    )
                }

                Box {
                    IconButton(
                        onClick = { showOptionsMenu = true },
                        modifier = Modifier.testTag("chat_overflow_options_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu Opsi Lainnya",
                            tint = TextPrimary
                        )
                    }

                    DropdownMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false },
                        modifier = Modifier.background(DarkSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Latih Jaringan Saraf (Training)", color = TextPrimary, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.ModelTraining, contentDescription = null, tint = NexoraCyan) },
                            onClick = {
                                showOptionsMenu = false
                                onNavigateToExperiments()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Impor Model PyTorch (.pt)", color = TextPrimary, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null, tint = NexoraViolet) },
                            onClick = {
                                showOptionsMenu = false
                                onNavigateToPyTorch?.invoke()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Dataset (Prompt → Reply)", color = TextPrimary, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, tint = NexoraEmerald) },
                            onClick = {
                                showOptionsMenu = false
                                onNavigateToDataset?.invoke()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Pengaturan On-Device", color = TextPrimary, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = TextSecondary) },
                            onClick = {
                                showOptionsMenu = false
                                onNavigateToSettings?.invoke()
                            }
                        )
                        HorizontalDivider(color = DarkOutline.copy(alpha = 0.5f))
                        DropdownMenuItem(
                            text = { Text("Bersihkan Chat", color = NexoraRose, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = NexoraRose) },
                            onClick = {
                                showOptionsMenu = false
                                viewModel.clearChat()
                            }
                        )
                    }
                }
            }
        )

        // Quick Action Prompt Chips (Generalist spectrum)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurfaceVariant.copy(alpha = 0.35f))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pintasan:",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontSize = 11.sp
            )
            SuggestionChip(
                onClick = { inputText = "status storage" },
                label = { Text("📁 Cek Storage Model", fontSize = 11.sp) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = DarkSurface,
                    labelColor = NexoraCyan
                ),
                border = SuggestionChipDefaults.suggestionChipBorder(
                    enabled = true,
                    borderColor = DarkOutline.copy(alpha = 0.5f)
                ),
                modifier = Modifier.testTag("chip_storage_check")
            )
            SuggestionChip(
                onClick = { inputText = "latih sebutir" },
                label = { Text("🥣 Belajar Sebutir-sebutir", fontSize = 11.sp) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = DarkSurface,
                    labelColor = NexoraAmber
                ),
                border = SuggestionChipDefaults.suggestionChipBorder(
                    enabled = true,
                    borderColor = DarkOutline.copy(alpha = 0.5f)
                ),
                modifier = Modifier.testTag("chip_incremental_train")
            )
            SuggestionChip(
                onClick = { inputText = "Berapa 128 × 42?" },
                label = { Text("Hitung 128 × 42", fontSize = 11.sp) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = DarkSurface,
                    labelColor = NexoraCyan
                ),
                border = SuggestionChipDefaults.suggestionChipBorder(
                    enabled = true,
                    borderColor = DarkOutline.copy(alpha = 0.5f)
                ),
                modifier = Modifier.testTag("chip_math_calc")
            )
            SuggestionChip(
                onClick = { inputText = "Buatkan fungsi Python untuk memfilter angka ganjil dan mengurutkannya" },
                label = { Text("Coding Python", fontSize = 11.sp) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = DarkSurface,
                    labelColor = NexoraEmerald
                ),
                border = SuggestionChipDefaults.suggestionChipBorder(
                    enabled = true,
                    borderColor = DarkOutline.copy(alpha = 0.5f)
                ),
                modifier = Modifier.testTag("chip_code_prompt")
            )
            SuggestionChip(
                onClick = { inputText = "Analisis kelebihan arsitektur AI local-first dibanding cloud" },
                label = { Text("Analisis AI Lokal", fontSize = 11.sp) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = DarkSurface,
                    labelColor = NexoraViolet
                ),
                border = SuggestionChipDefaults.suggestionChipBorder(
                    enabled = true,
                    borderColor = DarkOutline.copy(alpha = 0.5f)
                ),
                modifier = Modifier.testTag("chip_analysis_prompt")
            )
            SuggestionChip(
                onClick = { inputText = "file write: catat.txt, Catatan rahasia aman di sandbox" },
                label = { Text("Tulis File (Oversight)", fontSize = 11.sp) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = DarkSurface,
                    labelColor = NexoraAmber
                ),
                border = SuggestionChipDefaults.suggestionChipBorder(
                    enabled = true,
                    borderColor = DarkOutline.copy(alpha = 0.5f)
                ),
                modifier = Modifier.testTag("chip_oversight_test")
            )
        }

        // Message Area or Welcome State
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty() && !isGenerating) {
                // Generalist Welcome Card
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(NexoraCyanDark.copy(alpha = 0.4f))
                            .border(1.5.dp, NexoraCyan.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Nexora Core",
                            tint = NexoraCyan,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = "Nexora Intelligence Core v2",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Text(
                        text = "Agen Lokal Otonom Serba Bisa • Native Thinking Bar • 100% On-Device",
                        style = MaterialTheme.typography.bodySmall,
                        color = NexoraCyan,
                        fontSize = 11.sp
                    )

                    Spacer(Modifier.height(14.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DarkOutline.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Mulai Percakapan Multi-Domain:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary
                            )

                            StarterPromptRow(
                                title = "Hitung 128 × 42",
                                subtitle = "Kalkulasi deterministik & verifikasi memori",
                                onClick = { inputText = "Berapa 128 × 42?" }
                            )

                            StarterPromptRow(
                                title = "Coding & Algoritma",
                                subtitle = "Tulis script Python / Kotlin yang modular dan efisien",
                                onClick = { inputText = "Buatkan fungsi Kotlin untuk mengecek bilangan prima" }
                            )

                            StarterPromptRow(
                                title = "Siapa kamu?",
                                subtitle = "Penjelasan identitas agen lokal serba bisa",
                                onClick = { inputText = "Siapa kamu?" }
                            )

                            StarterPromptRow(
                                title = "Uji Coba Human Oversight",
                                subtitle = "Minta operasi penulisan file berisiko tinggi di sandbox",
                                onClick = { inputText = "file write: data.txt, Halo dari sandbox Nexora" }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { msg ->
                        ChatBubble(
                            message = msg,
                            onViewExperiment = onNavigateToExperiments,
                            onSaveToDataset = {
                                viewModel.saveChatMessagePairToDataset(
                                    userPrompt = "Konteks Chat #${msg.id}",
                                    assistantResponse = msg.text
                                ) { ok ->
                                    Toast.makeText(context, if (ok) "✓ Berhasil disimpan ke Dataset Pelatihan!" else "Gagal menyimpan", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }

                    // Live Streaming Bubble with Qwen-Style Thinking Bar
                    if (isGenerating && (streamingState.isThinking || streamingState.answerText.isNotEmpty() || streamingState.thinkingText.isNotEmpty())) {
                        item {
                            NexoraLiveThinkingBubble(
                                isThinking = streamingState.isThinking,
                                thinkingText = streamingState.thinkingText,
                                answerText = streamingState.answerText,
                                onSkipThinking = { viewModel.skipThinking() }
                            )
                        }
                    }
                }
            }
        }

        // Input Composer Bar
        Surface(
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Attachment Preview Banner (if any item attached)
                attachedItem?.let { att ->
                    Surface(
                        color = DarkSurfaceVariant.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, top = 8.dp)
                            .border(1.dp, NexoraCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (att.type == ChatAttachmentType.IMAGE) Icons.Default.Image else Icons.Default.Description,
                                contentDescription = null,
                                tint = if (att.type == ChatAttachmentType.IMAGE) NexoraViolet else NexoraCyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = att.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1
                                )
                                Text(
                                    text = if (isExtractingAttachmentText) "Mengekstrak teks via OCR / Parser..."
                                    else if (!att.contentText.isNullOrBlank()) "${if (att.type == ChatAttachmentType.IMAGE) "Teks OCR: " else "Teks: "}${att.contentText.length} karakter"
                                    else if (att.type == ChatAttachmentType.IMAGE) "Lampiran Foto • Siap dianalisis"
                                    else "Dokumen terpilih",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (!att.contentText.isNullOrBlank()) NexoraEmerald else TextSecondary
                                )
                            }

                            // Quick Button: Latih Inkremental langsung ke Model Utama dari teks OCR maupun Dokumen/PDF
                            if (!att.contentText.isNullOrBlank()) {
                                FilledTonalButton(
                                    onClick = {
                                        viewModel.ingestDocumentToDataset(att.name, att.contentText) { count ->
                                            Toast.makeText(context, "✓ Berhasil mengekstrak $count butir dataset. Memulai latihan ke model utama...", Toast.LENGTH_SHORT).show()
                                            viewModel.trainIncrementalStepByStep(
                                                saveAsAdapter = false,
                                                onProgress = { _, _, _, _ -> },
                                                onFinish = { res ->
                                                    Toast.makeText(context, "✓ Latihan selesai! Bobot model utama diperbarui permanen.", Toast.LENGTH_LONG).show()
                                                }
                                            )
                                            attachedItem = null
                                        }
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = NexoraCyan.copy(alpha = 0.2f),
                                        contentColor = NexoraCyan
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Latih ke Model Utama", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            IconButton(
                                onClick = { attachedItem = null },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Hapus Lampiran",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Tombol + (Upload File / Foto / Alur Pelatihan)
                    IconButton(
                        onClick = { showAttachmentMenu = true },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(DarkSurfaceVariant)
                            .testTag("attachment_plus_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Lampirkan Berkas atau Foto",
                            tint = NexoraCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                "Tanya Nexora (matematika, coding, analisis, file)...",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_input_field"),
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkSurfaceVariant.copy(alpha = 0.45f),
                            unfocusedContainerColor = DarkSurfaceVariant.copy(alpha = 0.45f),
                            focusedBorderColor = NexoraCyan,
                            unfocusedBorderColor = DarkOutline.copy(alpha = 0.45f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        maxLines = 3
                    )

                    if (isGenerating) {
                        IconButton(
                            onClick = { viewModel.stopGeneration() },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(NexoraRose)
                                .testTag("stop_generation_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Hentikan",
                                tint = DarkBackground,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        val canSend = inputText.isNotBlank() || attachedItem != null
                        IconButton(
                            onClick = {
                                if (canSend) {
                                    val fullMessage = if (attachedItem != null) {
                                        val att = attachedItem!!
                                        val userPrompt = if (inputText.isNotBlank()) inputText.trim() else "Tolong baca dan analisis berkas ini."
                                        val safeSnippet = att.contentText?.take(160)?.replace("\n", " ") ?: ""
                                        val safeContext = att.contentText?.take(4000) ?: ""
                                        val typeLabel = if (att.type == ChatAttachmentType.IMAGE) "FOTO_OCR" else "DOKUMEN"
                                        val charCount = att.contentText?.length ?: 0
                                        "[BERKAS_TERLAMPIR: ${att.name} | $typeLabel | $charCount | $safeSnippet]\n$userPrompt\n[KONTEKS_RAHASIA_DOKUMEN]\n$safeContext"
                                    } else {
                                        inputText.trim()
                                    }
                                    viewModel.sendMessage(fullMessage)
                                    inputText = ""
                                    attachedItem = null
                                }
                            },
                            enabled = canSend,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (canSend) NexoraCyan else DarkOutline.copy(alpha = 0.4f))
                                .testTag("send_message_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Kirim",
                                tint = if (canSend) DarkBackground else TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Menu Lampiran (+)
    if (showAttachmentMenu) {
        AlertDialog(
            onDismissRequest = { showAttachmentMenu = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, tint = NexoraCyan)
                    Text("Lampiran & Alur Data", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Option 1: File / Dokumen
                    Surface(
                        color = DarkSurfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAttachmentMenu = false
                                documentPickerLauncher.launch("*/*")
                            }
                            .border(1.dp, DarkOutline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, tint = NexoraCyan, modifier = Modifier.size(24.dp))
                            Column {
                                Text("Upload File / Dokumen", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                Text("Pilih .txt, .md, .json, .csv, PDF untuk dianalisis atau dilatihkan", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }

                    // Option 2: Foto / Gambar
                    Surface(
                        color = DarkSurfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAttachmentMenu = false
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                            .border(1.dp, DarkOutline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = NexoraViolet, modifier = Modifier.size(24.dp))
                            Column {
                                Text("Upload Foto / Gambar", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                Text("Pilih gambar dari galeri HP (Zero-permission Photo Picker)", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }

                    // Option 3: Pipeline Lengkap (Data -> Model -> Training -> .safetensors -> Inference)
                    Surface(
                        color = DarkSurfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAttachmentMenu = false
                                showLifecycleDialog = true
                            }
                            .border(1.dp, NexoraEmerald.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.ModelTraining, contentDescription = null, tint = NexoraEmerald, modifier = Modifier.size(24.dp))
                            Column {
                                Text("Alur: Data → Bobot (.safetensors)", fontWeight = FontWeight.Bold, color = NexoraEmerald, fontSize = 14.sp)
                                Text("Pipeline mandiri: data dipelajari sebutir-sebutir & langsung aktif", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAttachmentMenu = false }) {
                    Text("Tutup", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Modal Siklus Lengkap: Data → Arsitektur → Training → Bobot (.safetensors) → Inference
    if (showLifecycleDialog) {
        AlertDialog(
            onDismissRequest = { if (!isLifecycleRunning) showLifecycleDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.AccountTree, contentDescription = null, tint = NexoraEmerald)
                    Text("Pipeline Mandiri On-Device", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Siklus Utuh Tanpa Cloud & Tanpa API Key:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Surface(
                        color = DarkSurfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("1. 📂 DATA: ${trainingData.size} butir tersimpan di SQLite lokal", style = MaterialTheme.typography.labelSmall, color = NexoraCyan)
                            Text("2. 🧠 ARSITEKTUR: ${activeModel.name} (Frozen di RAM)", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                            Text("3. ⚙️ TRAINING: Belajar inkremental (Makan sebutir-sebutir)", style = MaterialTheme.typography.labelSmall, color = NexoraAmber)
                            Text("4. 💾 BOBOT TERSIMPAN: Format .safetensors & .bin di HP", style = MaterialTheme.typography.labelSmall, color = NexoraEmerald)
                            Text("5. 🚀 INFERENCE: Adapter aktif langsung memandu respons", style = MaterialTheme.typography.labelSmall, color = NexoraViolet)
                        }
                    }

                    if (isLifecycleRunning) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NexoraEmerald)
                            Text(
                                text = if (lifecycleStepText.isNotBlank()) lifecycleStepText else "Sedang memproses pelatihan sebutir-sebutir...",
                                style = MaterialTheme.typography.labelSmall,
                                color = NexoraCyan
                            )
                        }
                    } else {
                        Text(
                            text = "Tekan tombol di bawah untuk mencerna data dan menyimpan bobot adapter .safetensors baru ke storage internal HP Anda.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (trainingData.isEmpty()) {
                            Toast.makeText(context, "Dataset masih kosong! Upload berkas teks atau simpan dari chat dulu.", Toast.LENGTH_SHORT).show()
                        } else {
                            isLifecycleRunning = true
                            lifecycleStepText = "Memulai pelatihan inkremental model utama on-device..."
                            viewModel.trainIncrementalStepByStep(
                                saveAsAdapter = false,
                                onProgress = { step, total, label, loss ->
                                    lifecycleStepText = "Mencerna sampel $step/$total: $label (Loss: ${"%.4f".format(loss)})"
                                },
                                onFinish = { res ->
                                    isLifecycleRunning = false
                                    showLifecycleDialog = false
                                    Toast.makeText(context, "✓ Berhasil! Bobot model utama diperbarui permanen & siap inference.", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexoraEmerald, contentColor = DarkBackground),
                    enabled = !isLifecycleRunning
                ) {
                    Text("Jalankan Siklus Sekarang", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (!isLifecycleRunning) {
                    TextButton(onClick = { showLifecycleDialog = false }) {
                        Text("Batal", color = TextSecondary)
                    }
                }
            },
            containerColor = DarkSurface
        )
    }

    // MANDATORY HUMAN OVERSIGHT DIALOG (APPROVE / DENY)
    pendingOversight?.let { oversight ->
        AlertDialog(
            onDismissRequest = { viewModel.denyHumanOversight() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Oversight",
                        tint = NexoraAmber,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Human Oversight Required",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 17.sp
                    )
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Operasi berisiko tinggi terdeteksi sebelum eksekusi:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Surface(
                        color = DarkSurfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Alat: ${oversight.toolName}",
                                fontWeight = FontWeight.Bold,
                                color = NexoraCyan,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Alasan: ${oversight.riskReason}",
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "Parameter: ${oversight.parameters}",
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Text(
                        text = "Apakah Anda memberikan otorisasi untuk menjalankan operasi ini?",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.approveHumanOversight() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NexoraEmerald,
                        contentColor = DarkBackground
                    ),
                    modifier = Modifier.testTag("oversight_approve_button")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("APPROVE / IZINKAN", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.denyHumanOversight() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NexoraRose),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NexoraRose),
                    modifier = Modifier.testTag("oversight_deny_button")
                ) {
                    Text("DENY / TOLAK", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

/**
 * Qwen-App Pattern: Thinking Bar with ✦ icon, rotating status labels,
 * Skip button on the right, and expandable live reasoning drawer.
 */
@Composable
fun NexoraLiveThinkingBubble(
    isThinking: Boolean,
    thinkingText: String,
    answerText: String,
    onSkipThinking: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(true) }
    var statusIndex by remember { mutableIntStateOf(0) }
    val rotatingLabels = remember {
        listOf(
            "Berpikir...",
            "Menganalisis konteks...",
            "Merumuskan penalaran...",
            "Memeriksa memori lokal..."
        )
    }

    LaunchedEffect(isThinking) {
        while (isThinking) {
            delay(1600)
            statusIndex = (statusIndex + 1) % rotatingLabels.size
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ✦ THINKING BAR
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DarkSurfaceVariant.copy(alpha = 0.65f),
            border = androidx.compose.foundation.BorderStroke(1.dp, NexoraCyan.copy(alpha = 0.45f)),
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { isExpanded = !isExpanded }
                    ) {
                        Text(
                            text = "✦",
                            color = NexoraCyan,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isThinking) rotatingLabels[statusIndex] else "Proses berpikir selesai",
                            color = if (isThinking) NexoraCyan else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Lipat" else "Buka",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    if (isThinking) {
                        FilledTonalButton(
                            onClick = onSkipThinking,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = NexoraCyan.copy(alpha = 0.2f),
                                contentColor = NexoraCyan
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier
                                .height(26.dp)
                                .testTag("skip_thinking_button")
                        ) {
                            Text("Skip", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Expandable Thinking Content Box
                AnimatedVisibility(visible = isExpanded && thinkingText.isNotBlank()) {
                    Surface(
                        color = DarkSurface.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Text(
                            text = thinkingText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = TextSecondary,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }

        // Final Answer Bubble (Streamed after thinking finishes or concurrently)
        if (answerText.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = DarkSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline.copy(alpha = 0.5f)),
                modifier = Modifier.widthIn(min = 40.dp, max = 320.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = "$answerText ▋",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp
                        ),
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun StarterPromptRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .background(DarkSurfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = NexoraCyan
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontSize = 10.sp
            )
        }
        Icon(
            imageVector = Icons.Default.ArrowForward,
            contentDescription = null,
            tint = NexoraCyan,
            modifier = Modifier.size(14.dp)
        )
    }
}

data class AttachmentBubbleData(
    val fileName: String,
    val typeLabel: String,
    val charCount: Int,
    val snippet: String,
    val userQuestion: String
)

@Composable
fun AttachmentCompactCard(
    fileName: String,
    typeLabel: String,
    charCount: Int,
    snippet: String
) {
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DarkSurfaceVariant.copy(alpha = 0.7f),
        border = androidx.compose.foundation.BorderStroke(1.dp, NexoraCyan.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (typeLabel.contains("FOTO")) Icons.Default.Image else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (typeLabel.contains("FOTO")) NexoraViolet else NexoraCyan,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${if (typeLabel.contains("FOTO")) "Foto OCR" else "Berkas Dokumen"} • %,d karakter".format(charCount),
                        fontSize = 10.sp,
                        color = NexoraEmerald
                    )
                }
                if (snippet.isNotBlank()) {
                    TextButton(
                        onClick = { isExpanded = !isExpanded },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(if (isExpanded) "Tutup" else "Cuplikan", fontSize = 10.sp, color = NexoraCyan)
                    }
                }
            }
            if (isExpanded && snippet.isNotBlank()) {
                Surface(
                    color = DarkBackground.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "\"${snippet.take(160)}...\"",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = TextSecondary,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessageEntity,
    onViewExperiment: () -> Unit,
    onSaveToDataset: () -> Unit = {}
) {
    val isUser = message.sender == "USER"
    val isSystem = message.sender == "SYSTEM"

    // Parse <think>...</think> in historical messages
    val rawText = message.text
    val hasThink = rawText.contains("<think>") && rawText.contains("</think>")
    val thinkingSegment = if (hasThink) rawText.substringAfter("<think>").substringBefore("</think>").trim() else ""
    val answerSegment = if (hasThink) rawText.substringAfter("</think>").trimStart() else rawText

    val isStructuredAttachment = answerSegment.startsWith("[BERKAS_TERLAMPIR:")
    val isLegacyAttachment = answerSegment.startsWith("[Lampiran:") || (answerSegment.contains("[Lampiran") && answerSegment.contains("\"\"\""))

    val parsedAttachment = remember(answerSegment) {
        if (isStructuredAttachment) {
            try {
                val header = answerSegment.substringAfter("[BERKAS_TERLAMPIR:").substringBefore("]")
                val parts = header.split("|").map { it.trim() }
                val fName = parts.getOrNull(0) ?: "Berkas"
                val fType = parts.getOrNull(1) ?: "DOKUMEN"
                val fChars = parts.getOrNull(2)?.toIntOrNull() ?: 0
                val fSnippet = parts.getOrNull(3) ?: ""
                val remainingText = answerSegment.substringAfter("]\n", "").ifBlank {
                    answerSegment.substringAfter("]", "").trim()
                }
                AttachmentBubbleData(
                    fileName = fName,
                    typeLabel = fType,
                    charCount = fChars,
                    snippet = fSnippet,
                    userQuestion = remainingText
                )
            } catch (_: Exception) {
                null
            }
        } else if (isLegacyAttachment) {
            try {
                val fName = answerSegment.substringAfter("[Lampiran:").substringBefore("•").substringBefore("]").trim()
                val question = answerSegment.substringAfter("Pertanyaan:", "").ifBlank {
                    answerSegment.substringAfterLast("\"\"\"", "").trim()
                }
                AttachmentBubbleData(
                    fileName = if (fName.isNotBlank()) fName else "Dokumen Lampiran",
                    typeLabel = "DOKUMEN",
                    charCount = 1000,
                    snippet = "",
                    userQuestion = if (question.isNotBlank()) question else "Tolong analisis isi berkas di atas."
                )
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    var isThinkingExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isUser && !isSystem) {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp, bottom = 2.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(NexoraCyanDark.copy(alpha = 0.6f))
                    .border(1.dp, NexoraCyan.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Nexora",
                    tint = NexoraCyan,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        Column(
            modifier = Modifier.widthIn(min = 40.dp, max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // If historical message has thinking block, show collapsible Thinking Bar
            if (hasThink && thinkingSegment.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DarkSurfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, NexoraCyan.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { isThinkingExpanded = !isThinkingExpanded }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("✦", color = NexoraCyan, fontSize = 12.sp)
                                Text("Proses Berpikir", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                            Icon(
                                imageVector = if (isThinkingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        if (isThinkingExpanded) {
                            Text(
                                text = thinkingSegment,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = TextMuted,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(
                    topStart = 14.dp,
                    topEnd = 14.dp,
                    bottomStart = if (isUser) 14.dp else 3.dp,
                    bottomEnd = if (isUser) 3.dp else 14.dp
                ),
                color = when {
                    isUser -> NexoraCyanDark.copy(alpha = 0.55f)
                    isSystem -> NexoraVioletDark.copy(alpha = 0.35f)
                    else -> DarkSurface
                },
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when {
                        isUser -> NexoraCyan.copy(alpha = 0.4f)
                        isSystem -> NexoraViolet.copy(alpha = 0.4f)
                        else -> DarkOutline.copy(alpha = 0.5f)
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (!isUser) {
                        Text(
                            text = if (isSystem) "System" else "Nexora",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp
                            ),
                            color = if (isSystem) NexoraViolet else NexoraCyan
                        )
                    }

                    if (parsedAttachment != null) {
                        AttachmentCompactCard(
                            fileName = parsedAttachment.fileName,
                            typeLabel = parsedAttachment.typeLabel,
                            charCount = parsedAttachment.charCount,
                            snippet = parsedAttachment.snippet
                        )
                        if (parsedAttachment.userQuestion.isNotBlank()) {
                            Text(
                                text = parsedAttachment.userQuestion,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.sp
                                ),
                                color = TextPrimary
                            )
                        }
                    } else {
                        Text(
                            text = answerSegment,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.5.sp,
                                lineHeight = 19.sp
                            ),
                            color = TextPrimary
                        )
                    }

                    if (message.experimentId != null) {
                        OutlinedButton(
                            onClick = onViewExperiment,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NexoraCyan.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NexoraCyan)
                        ) {
                            Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Lihat Detail Eksperimen", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (!isUser && !isSystem) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onSaveToDataset,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.PostAdd, contentDescription = null, tint = NexoraCyan, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Simpan ke Dataset", fontSize = 10.5.sp, color = NexoraCyan)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatContentPreview(
    messages: List<ChatMessageEntity>
) {
    var inputText by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .imePadding()
    ) {
        NexoraHeader(
            title = "Nexora Chat",
            activeModelName = "Nexora Core Quantized",
            isOffline = true,
            onBack = {}
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages) { msg ->
                    ChatBubble(message = msg, onViewExperiment = {})
                }
            }
        }
        Surface(
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Tanya Nexora...", color = TextMuted, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurfaceVariant.copy(alpha = 0.45f),
                        unfocusedContainerColor = DarkSurfaceVariant.copy(alpha = 0.45f),
                        focusedBorderColor = NexoraCyan,
                        unfocusedBorderColor = DarkOutline.copy(alpha = 0.45f)
                    )
                )
                IconButton(
                    onClick = {},
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(NexoraCyan)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Kirim", tint = DarkBackground, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Preview(name = "Chat - Layar Kecil (320x640)", widthDp = 320, heightDp = 640, showBackground = true)
@Composable
fun ChatPreviewSmallScreen() {
    MyApplicationTheme {
        ChatContentPreview(
            messages = listOf(
                ChatMessageEntity(sender = "NEXORA", text = "Nexora Intelligence Core siap di layar kecil.")
            )
        )
    }
}

@Preview(name = "Chat - Layar Normal (390x844)", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun ChatPreviewNormalScreen() {
    MyApplicationTheme {
        ChatContentPreview(
            messages = listOf(
                ChatMessageEntity(sender = "USER", text = "Berapa 128 × 42?"),
                ChatMessageEntity(sender = "NEXORA", text = "Hasil kalkulasi deterministik adalah 5,376.")
            )
        )
    }
}

@Preview(name = "Chat - Tablet (800x1280)", widthDp = 800, heightDp = 1280, showBackground = true)
@Composable
fun ChatPreviewTabletScreen() {
    MyApplicationTheme {
        ChatContentPreview(
            messages = listOf(
                ChatMessageEntity(sender = "USER", text = "Analisis arsitektur lokal pada tablet."),
                ChatMessageEntity(sender = "NEXORA", text = "Pada layar tablet, input bar tetap terikat di bawah tanpa gap liar.")
            )
        )
    }
}

