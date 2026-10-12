package com.example.nexora.ui.screens.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nexora.engine.tool.NexoraTool
import com.example.nexora.ui.components.NexoraHeader
import com.example.nexora.ui.components.StatusBadge
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.ui.theme.*

@Composable
fun ToolsScreen(
    viewModel: NexoraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val tools by viewModel.toolsState.collectAsStateWithLifecycle()
    val toolHistory by viewModel.toolHistory.collectAsStateWithLifecycle()
    val activeModel by viewModel.activeModelInfo.collectAsStateWithLifecycle()
    val isOffline by viewModel.offlineEnforced.collectAsStateWithLifecycle()
    val testResult by viewModel.toolTestResult.collectAsStateWithLifecycle()

    var testingTool by remember { mutableStateOf<NexoraTool?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            NexoraHeader(
                title = "Tool Registry",
                activeModelName = activeModel.name,
                isOffline = isOffline,
                onBack = onBack
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
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurface,
                contentColor = NexoraCyan
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Modular Tools (${tools.size})", fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Call Logs (${toolHistory.size})", fontSize = 12.sp) }
                )
            }

            if (selectedTab == 0) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(tools, key = { it.name }) { tool ->
                        ToolCard(
                            tool = tool,
                            onToggle = { enabled -> viewModel.toggleTool(tool.name, enabled) },
                            onTest = { testingTool = tool }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    if (toolHistory.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No tool execution logs recorded yet.", color = TextSecondary)
                            }
                        }
                    } else {
                        items(toolHistory) { entry ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DarkOutline.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(entry.toolName, fontWeight = FontWeight.Bold, color = NexoraCyan)
                                        StatusBadge(
                                            text = if (entry.isError) "ERROR" else "${entry.executionTimeMs}ms",
                                            color = if (entry.isError) NexoraRose else NexoraEmerald
                                        )
                                    }
                                    Text("Input: ${entry.inputParams}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    Text(
                                        "Output: ${if (entry.isError) entry.errorMessage else entry.outputResult}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (entry.isError) NexoraRose else TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Interactive Test Run Modal
    testingTool?.let { tool ->
        val paramInputs = remember { mutableStateMapOf<String, String>() }
        tool.inputSchema.forEach { schema ->
            if (!paramInputs.containsKey(schema.name)) {
                paramInputs[schema.name] = when (schema.name) {
                    "expression" -> "sqrt(144) * 2"
                    "action" -> "all"
                    "code" -> if (tool.name == "python_exec") {
                        "def is_palindrome(s):\n    cleaned = ''.join(c.lower() for c in str(s) if c.isalnum())\n    return cleaned == cleaned[::-1]"
                    } else "fibonacci(10)"
                    "test_cases" -> "is_palindrome('racecar') == True\nis_palindrome('hello') == False"
                    "timeout_ms" -> "5000"
                    else -> "test"
                }
            }
        }

        AlertDialog(
            onDismissRequest = { testingTool = null },
            title = {
                Text("Test Tool: ${tool.name}", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tool.description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)

                    tool.inputSchema.forEach { schema ->
                        OutlinedTextField(
                            value = paramInputs[schema.name] ?: "",
                            onValueChange = { paramInputs[schema.name] = it },
                            label = { Text("${schema.name} (${schema.type})") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    testResult?.let { res ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (res.success) NexoraEmerald.copy(alpha = 0.15f) else NexoraRose.copy(alpha = 0.15f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (res.success) "Success (${res.executionTimeMs}ms)" else "Fault (${res.executionTimeMs}ms)",
                                    fontWeight = FontWeight.Bold,
                                    color = if (res.success) NexoraEmerald else NexoraRose
                                )
                                Text(
                                    res.output.ifBlank { res.error ?: "No output" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.executeTestTool(tool.name, paramInputs) },
                    colors = ButtonDefaults.buttonColors(containerColor = NexoraCyan, contentColor = DarkBackground)
                ) {
                    Text("Execute")
                }
            },
            dismissButton = {
                TextButton(onClick = { testingTool = null }) {
                    Text("Close", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun ToolCard(
    tool: NexoraTool,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkOutline.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp)
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
                Column {
                    Text(
                        text = tool.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (tool.isInternetRequired) "Internet Tool (Optional)" else "Local On-Device Tool",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tool.isInternetRequired) NexoraAmber else NexoraCyan
                    )
                }

                Switch(
                    checked = tool.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = NexoraCyan),
                    modifier = Modifier.testTag("tool_toggle_${tool.name.lowercase()}")
                )
            }

            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            // Input / Output Schema Badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Inputs:", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                tool.inputSchema.forEach {
                    StatusBadge(text = "${it.name}:${it.type}", color = NexoraViolet)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                FilledTonalButton(
                    onClick = onTest,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = NexoraCyan.copy(alpha = 0.15f),
                        contentColor = NexoraCyan
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Test Sandbox", fontSize = 12.sp)
                }
            }
        }
    }
}
