package com.example.nexora.ui.screens.memory

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nexora.model.database.MemoryEntity
import com.example.nexora.ui.components.NexoraHeader
import com.example.nexora.ui.components.StatusBadge
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(
    viewModel: NexoraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val memories by viewModel.allMemories.collectAsStateWithLifecycle()
    val activeModel by viewModel.activeModelInfo.collectAsStateWithLifecycle()
    val isOffline by viewModel.offlineEnforced.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryEntity?>(null) }
    var exportJsonContent by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    val categories = listOf(
        "ALL" to "All Memories",
        "LONG_TERM_KNOWLEDGE" to "Knowledge",
        "USER_INFO" to "User Data",
        "SUCCESSFUL_STRATEGY" to "Successes",
        "FAILED_STRATEGY" to "Failures",
        "LESSON_LEARNED" to "Lessons",
        "TOOL_HISTORY" to "Tool Logs",
        "TRAINING_DATA" to "Training Data"
    )

    val filteredMemories = memories.filter { mem ->
        val matchesCategory = (selectedCategory == "ALL" || mem.type == selectedCategory)
        val matchesQuery = searchQuery.isBlank() ||
                mem.title.contains(searchQuery, ignoreCase = true) ||
                mem.content.contains(searchQuery, ignoreCase = true) ||
                mem.tags.contains(searchQuery, ignoreCase = true)
        matchesCategory && matchesQuery
    }

    Scaffold(
        topBar = {
            NexoraHeader(
                title = "Persistent Memory",
                activeModelName = activeModel.name,
                isOffline = isOffline,
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                exportJsonContent = viewModel.exportMemoriesJson()
                            }
                        },
                        modifier = Modifier.testTag("export_memories_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Export JSON", tint = NexoraCyan)
                    }
                    IconButton(
                        onClick = { showImportDialog = true },
                        modifier = Modifier.testTag("import_memories_button")
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = "Import JSON", tint = NexoraViolet)
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Store Memory") },
                containerColor = NexoraCyan,
                contentColor = DarkBackground,
                modifier = Modifier.testTag("fab_add_memory")
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search memories, facts, lessons, tags...", color = TextMuted, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("search_memory_input"),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NexoraCyan,
                    unfocusedBorderColor = DarkOutline,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                singleLine = true
            )

            // Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { (catKey, label) ->
                    val isSelected = selectedCategory == catKey
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = catKey },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NexoraCyan.copy(alpha = 0.2f),
                            selectedLabelColor = NexoraCyan,
                            containerColor = DarkSurface,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) NexoraCyan else DarkOutline
                        )
                    )
                }
            }

            // Memory Cards List
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 88.dp)
            ) {
                if (filteredMemories.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No memories found matching criteria.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    items(filteredMemories) { memory ->
                        MemoryItemCard(
                            memory = memory,
                            onEdit = { editingMemory = memory },
                            onDelete = { viewModel.deleteMemory(memory.id) }
                        )
                    }
                }
            }
        }
    }

    // Add Memory Dialog
    if (showAddDialog) {
        var title by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        var tags by remember { mutableStateOf("knowledge,custom") }
        var type by remember { mutableStateOf("LONG_TERM_KNOWLEDGE") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Store Persistent Memory", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("Insight / Knowledge / Heuristic") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = { Text("Tags (comma separated)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (title.isNotBlank() && content.isNotBlank()) {
                            viewModel.addMemory(type, title, content, tags)
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexoraCyan, contentColor = DarkBackground)
                ) {
                    Text("Save to Memory")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Edit Memory Dialog
    editingMemory?.let { memory ->
        var editTitle by remember { mutableStateOf(memory.title) }
        var editContent by remember { mutableStateOf(memory.content) }
        var editTags by remember { mutableStateOf(memory.tags) }

        AlertDialog(
            onDismissRequest = { editingMemory = null },
            title = { Text("Edit Memory", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text("Content") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    OutlinedTextField(
                        value = editTags,
                        onValueChange = { editTags = it },
                        label = { Text("Tags") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateMemory(
                            memory.copy(title = editTitle, content = editContent, tags = editTags)
                        )
                        editingMemory = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexoraCyan, contentColor = DarkBackground)
                ) {
                    Text("Update")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMemory = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Export JSON Dialog
    exportJsonContent?.let { json ->
        AlertDialog(
            onDismissRequest = { exportJsonContent = null },
            title = { Text("Exported Memory JSON", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = json,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    maxLines = 14
                )
            },
            confirmButton = {
                TextButton(onClick = { exportJsonContent = null }) {
                    Text("Close", color = NexoraCyan)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Import JSON Dialog
    if (showImportDialog) {
        var importInput by remember { mutableStateOf("") }
        var importCount by remember { mutableStateOf<Int?>(null) }

        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Memories JSON", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Paste valid JSON array of memories to merge into local SQLite memory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = importInput,
                        onValueChange = { importInput = it },
                        placeholder = { Text("[{\"type\": \"...\", \"title\": \"...\"}]") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6
                    )
                    if (importCount != null) {
                        Text("Successfully imported $importCount memories!", color = NexoraEmerald)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val count = viewModel.importMemoriesJson(importInput)
                            importCount = count
                            if (count > 0) {
                                showImportDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexoraViolet, contentColor = DarkBackground)
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun MemoryItemCard(
    memory: MemoryEntity,
    onEdit: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(
                    text = memory.type.replace("_", " "),
                    color = when (memory.type) {
                        "SUCCESSFUL_STRATEGY" -> NexoraEmerald
                        "FAILED_STRATEGY" -> NexoraRose
                        "LESSON_LEARNED" -> NexoraAmber
                        "LONG_TERM_KNOWLEDGE" -> NexoraCyan
                        else -> NexoraViolet
                    }
                )

                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = NexoraRose, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Text(
                text = memory.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            if (memory.tags.isNotBlank()) {
                Text(
                    text = "Tags: ${memory.tags}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NexoraCyan,
                    fontSize = 10.sp
                )
            }
        }
    }
}
