package com.example.nexora.ui.screens.dataset

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nexora.model.database.TrainingDataEntity
import com.example.nexora.ui.components.NexoraHeader
import com.example.nexora.ui.components.StatusBadge
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DatasetScreen(
    viewModel: NexoraViewModel,
    onBack: () -> Unit,
    onNavigateToTraining: () -> Unit,
    onOpenMenu: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val activeModel by viewModel.activeModelInfo.collectAsStateWithLifecycle()
    val isOffline by viewModel.offlineEnforced.collectAsStateWithLifecycle()
    val dataset by viewModel.trainingData.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var newPrompt by remember { mutableStateOf("") }
    var newReply by remember { mutableStateOf("") }
    var showPasteImportDialog by remember { mutableStateOf(false) }
    var rawPasteText by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val text = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (text.isNotBlank()) {
                    var addedCount = 0
                    val lines = text.lines()
                    for (line in lines) {
                        val clean = line.trim()
                        if (clean.isBlank() || clean.startsWith("#") || clean.startsWith("prompt,reply") || clean.startsWith("text,label")) continue
                        
                        val pair: Pair<String, String>? = when {
                            clean.contains("|||") -> {
                                val parts = clean.split("|||", limit = 2)
                                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                            }
                            clean.contains("\t") -> {
                                val parts = clean.split("\t", limit = 2)
                                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                            }
                            clean.contains(",") -> {
                                val parts = clean.split(",", limit = 2)
                                if (parts.size == 2) parts[0].trim().removeSurrounding("\"") to parts[1].trim().removeSurrounding("\"") else null
                            }
                            else -> null
                        }

                        if (pair != null && pair.first.isNotBlank() && pair.second.isNotBlank()) {
                            viewModel.addTrainingPair(pair.first, pair.second) {}
                            addedCount++
                        }
                    }
                    Toast.makeText(context, "✓ Berhasil mengimpor $addedCount pasangan prompt–reply", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal membaca berkas: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val filteredList = remember(dataset, searchQuery) {
        if (searchQuery.isBlank()) dataset
        else dataset.filter {
            it.prompt.contains(searchQuery, ignoreCase = true) ||
            it.completion.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            NexoraHeader(
                title = "Dataset (Prompt → Reply)",
                activeModelName = activeModel.name,
                isOffline = isOffline,
                onBack = onBack,
                onMenuClick = onOpenMenu,
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Tambah Data", tint = NexoraCyan)
                    }
                }
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
        ) {
            // 1. Stats Bar Ala zegamingx/Neural
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val rowCount = dataset.size
                    val totalChars = dataset.sumOf { it.prompt.length + it.completion.length }
                    val uniqueReplies = dataset.map { it.completion.trim().lowercase() }.distinct().size

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, NexoraCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("DATASET", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                            Text(
                                text = if (rowCount > 0) "$rowCount baris" else "belum ada",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = NexoraCyan
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, NexoraViolet.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("REPLY UNIK", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                            Text(
                                text = if (uniqueReplies > 0) "$uniqueReplies pola" else "belum ada",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = NexoraViolet
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, NexoraEmerald.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("TOTAL KARAKTER", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                            Text(
                                text = if (totalChars > 0) "$totalChars char" else "0",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = NexoraEmerald
                            )
                        }
                    }
                }
            }

            // 2. Toolbar Aksi Impor / Ekspor
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("text/*", "text/csv", "application/csv", "*/*"))
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NexoraCyan)
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Impor CSV/TXT", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = { showPasteImportDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NexoraAmber)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tempel Teks", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = onNavigateToTraining,
                        modifier = Modifier.weight(1.1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NexoraCyan, contentColor = DarkBackground)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Latih Model", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 3. Search Box
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Cari prompt atau reply...", fontSize = 13.sp, color = TextMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_dataset_field"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface,
                        focusedBorderColor = NexoraCyan,
                        unfocusedBorderColor = DarkOutline
                    ),
                    singleLine = true
                )
            }

            // 4. Daftar Baris Dataset Prompt -> Reply
            if (filteredList.isEmpty()) {
                item {
                    Surface(
                        color = DarkSurfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (dataset.isEmpty()) "belum ada data pelatihan" else "Tidak ada data yang cocok dengan pencarian",
                                color = TextMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (dataset.isEmpty()) {
                                Text(
                                    text = "Tekan tombol + di atas atau 'Impor CSV/TXT' untuk menambahkan pasangan prompt dan reply.",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            } else {
                items(filteredList, key = { it.id }) { item ->
                    DatasetPairCard(
                        item = item,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString("${item.prompt} ||| ${item.completion}"))
                            Toast.makeText(context, "Tersalin ke clipboard", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = {
                            viewModel.deleteTrainingData(item.id)
                            Toast.makeText(context, "Data dihapus", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    // Dialog Tambah Manual Pasangan Prompt -> Reply
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Tambah Pasangan Prompt → Reply", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Tentukan input prompt pengguna dan respon/reply yang diharapkan dari model:", fontSize = 12.sp, color = TextSecondary)
                    OutlinedTextField(
                        value = newPrompt,
                        onValueChange = { newPrompt = it },
                        label = { Text("Prompt Pengguna") },
                        placeholder = { Text("Contoh: Apa ibukota Indonesia?") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = newReply,
                        onValueChange = { newReply = it },
                        label = { Text("Reply / Jawaban yang Diharapkan") },
                        placeholder = { Text("Contoh: Ibukota Indonesia adalah Nusantara (IKN).") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPrompt.isNotBlank() && newReply.isNotBlank()) {
                            viewModel.addTrainingPair(newPrompt, newReply) {
                                Toast.makeText(context, "Pasangan berhasil ditambahkan", Toast.LENGTH_SHORT).show()
                                newPrompt = ""
                                newReply = ""
                                showAddDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexoraCyan, contentColor = DarkBackground)
                ) {
                    Text("Simpan Data", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Batal", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Dialog Tempel Teks Batch Ala zegamingx/Neural
    if (showPasteImportDialog) {
        AlertDialog(
            onDismissRequest = { showPasteImportDialog = false },
            title = { Text("Tempel Teks Pasangan Data", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Format yang didukung:\n• `prompt ||| reply`\n• `prompt <TAB> reply`\n• `prompt,reply` (CSV)",
                        fontSize = 11.sp,
                        color = NexoraCyan,
                        lineHeight = 15.sp
                    )
                    OutlinedTextField(
                        value = rawPasteText,
                        onValueChange = { rawPasteText = it },
                        placeholder = { Text("halo ||| halo juga, ada yang bisa dibantu?\nsiapa kamu ||| Saya Nexora AI on-device.") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (rawPasteText.isNotBlank()) {
                            var added = 0
                            rawPasteText.lines().forEach { line ->
                                val clean = line.trim()
                                if (clean.isNotBlank()) {
                                    val pair: Pair<String, String>? = when {
                                        clean.contains("|||") -> {
                                            val p = clean.split("|||", limit = 2)
                                            if (p.size == 2) p[0].trim() to p[1].trim() else null
                                        }
                                        clean.contains("\t") -> {
                                            val p = clean.split("\t", limit = 2)
                                            if (p.size == 2) p[0].trim() to p[1].trim() else null
                                        }
                                        clean.contains(",") -> {
                                            val p = clean.split(",", limit = 2)
                                            if (p.size == 2) p[0].trim() to p[1].trim() else null
                                        }
                                        else -> null
                                    }
                                    if (pair != null && pair.first.isNotBlank() && pair.second.isNotBlank()) {
                                        viewModel.addTrainingPair(pair.first, pair.second) {}
                                        added++
                                    }
                                }
                            }
                            Toast.makeText(context, "✓ Berhasil menambah $added data", Toast.LENGTH_SHORT).show()
                            rawPasteText = ""
                            showPasteImportDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexoraCyan, contentColor = DarkBackground)
                ) {
                    Text("Impor", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteImportDialog = false }) {
                    Text("Batal", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
private fun DatasetPairCard(
    item: TrainingDataEntity,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkOutline.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(text = "PROMPT → REPLY", color = NexoraCyan)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Salin", tint = TextMuted, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = NexoraRose.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Prompt:",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted
                )
                Text(
                    text = item.prompt,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSurfaceVariant.copy(alpha = 0.5f))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Expected Reply:",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = NexoraViolet
                )
                Text(
                    text = item.completion,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
