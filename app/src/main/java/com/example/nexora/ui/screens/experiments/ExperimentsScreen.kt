package com.example.nexora.ui.screens.experiments

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nexora.engine.experiment.ExperimentPhase
import com.example.nexora.model.database.ExperimentEntity
import com.example.nexora.ui.components.NexoraHeader
import com.example.nexora.ui.components.StatusBadge
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.ui.theme.*

@Composable
fun ExperimentsScreen(
    viewModel: NexoraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val experiments by viewModel.allExperiments.collectAsStateWithLifecycle()
    val progress by viewModel.experimentProgress.collectAsStateWithLifecycle()
    val activeModel by viewModel.activeModelInfo.collectAsStateWithLifecycle()
    val isOffline by viewModel.offlineEnforced.collectAsStateWithLifecycle()
    val maxRetries by viewModel.maxRetries.collectAsStateWithLifecycle()

    var showNewExperimentDialog by remember { mutableStateOf(false) }
    var selectedExperiment by remember { mutableStateOf<ExperimentEntity?>(null) }

    Scaffold(
        topBar = {
            NexoraHeader(
                title = "Experiment Engine",
                activeModelName = activeModel.name,
                isOffline = isOffline,
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = { showNewExperimentDialog = true },
                        modifier = Modifier.testTag("launch_new_experiment_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircle,
                            contentDescription = "New Experiment",
                            tint = NexoraCyan
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewExperimentDialog = true },
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                text = { Text("Launch Experiment") },
                containerColor = NexoraCyan,
                contentColor = DarkBackground,
                modifier = Modifier.testTag("fab_launch_experiment")
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
            contentPadding = PaddingValues(top = 16.dp, bottom = 88.dp)
        ) {
            // Live Autonomous Experiment Runner Status Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (progress.phase == ExperimentPhase.SUCCESS) NexoraEmerald.copy(alpha = 0.5f)
                            else if (progress.phase == ExperimentPhase.FAILED) NexoraRose.copy(alpha = 0.5f)
                            else NexoraCyan.copy(alpha = 0.4f),
                            RoundedCornerShape(16.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp)
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
                                text = "Autonomous Loop Engine",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            StatusBadge(
                                text = progress.phase.name,
                                color = when (progress.phase) {
                                    ExperimentPhase.SUCCESS -> NexoraEmerald
                                    ExperimentPhase.FAILED -> NexoraRose
                                    ExperimentPhase.IDLE -> TextSecondary
                                    else -> NexoraAmber
                                }
                            )
                        }

                        Text(
                            text = "PLAN → EXECUTE → OBSERVE → EVALUATE → REMEMBER → MODIFY → RETRY",
                            style = MaterialTheme.typography.labelSmall,
                            color = NexoraCyan,
                            fontSize = 10.sp
                        )

                        if (progress.phase != ExperimentPhase.IDLE) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = NexoraCyan,
                                trackColor = DarkSurfaceHighlight
                            )
                        }

                        Text(
                            text = progress.statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )

                        if (progress.currentAttempt > 0) {
                            Text(
                                text = "Iteration: Attempt ${progress.currentAttempt} of ${progress.maxAttempts} | Strategy: ${progress.currentStrategyId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }

                        // Recent Cycle Logs
                        if (progress.logs.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    progress.logs.takeLast(4).forEach { logLine ->
                                        Text(
                                            text = logLine,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary,
                                            fontSize = 11.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Experiment Registry (${experiments.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            if (experiments.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No experiments executed yet. Tap 'Launch Experiment' to start autonomous experimentation.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(experiments) { exp ->
                    ExperimentItemCard(
                        experiment = exp,
                        onClick = { selectedExperiment = exp }
                    )
                }
            }
        }
    }

    // Launch New Experiment Dialog
    if (showNewExperimentDialog) {
        var goalText by remember { mutableStateOf("Verify numeric synthesis for sqrt(256) * 16") }
        var inputPayload by remember { mutableStateOf("sqrt(256) * 16") }
        var demonstrateFailureSynthesis by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showNewExperimentDialog = false },
            title = {
                Text("Launch Autonomous Experiment", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Nexora will formulate Strategy 1. If it fails, it will diagnose the failure and synthesize Strategy 3 from Strategy 1 + Strategy 2.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = goalText,
                        onValueChange = { goalText = it },
                        label = { Text("Experiment Goal") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = inputPayload,
                        onValueChange = { inputPayload = it },
                        label = { Text("Input / Data Payload") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Demonstrate Multi-Strategy Failure & Synthesis",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                "Simulates Strategy 1 failure to watch Nexora synthesize Strategy 3 automatically",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = demonstrateFailureSynthesis,
                            onCheckedChange = { demonstrateFailureSynthesis = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = NexoraCyan)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNewExperimentDialog = false
                        viewModel.launchExperiment(
                            goal = goalText,
                            input = inputPayload,
                            forceFailureForTesting = demonstrateFailureSynthesis
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexoraCyan, contentColor = DarkBackground)
                ) {
                    Text("Run Experiment")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewExperimentDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Detail Dialog
    selectedExperiment?.let { exp ->
        AlertDialog(
            onDismissRequest = { selectedExperiment = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(exp.experimentId, fontWeight = FontWeight.Bold, color = TextPrimary)
                    StatusBadge(
                        text = "${exp.verificationStatus} (${exp.evaluationScore}%)",
                        color = when (exp.verificationStatus) {
                            "SUCCESS" -> NexoraEmerald
                            "PARTIAL" -> NexoraAmber
                            "NOT_VERIFIED" -> NexoraViolet
                            else -> NexoraRose
                        }
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Objective:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(exp.goal, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)

                    Text("Input Payload:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(exp.input, style = MaterialTheme.typography.bodySmall, color = NexoraCyan)

                    Text("Operation:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(exp.operation.ifBlank { "N/A" }, style = MaterialTheme.typography.bodySmall, color = TextPrimary)

                    Text("Final Observable Answer:", style = MaterialTheme.typography.labelSmall, color = NexoraEmerald)
                    Text(
                        text = exp.finalHumanReadableAnswer.ifBlank { exp.actualResult },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NexoraCyan
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Expected Result:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Text(exp.expectedResult.ifBlank { "N/A" }, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Attempt Number:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Text("Attempt #${exp.attemptNumber}", style = MaterialTheme.typography.bodySmall, color = NexoraAmber)
                        }
                    }

                    Text("Strategy Used:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text("${exp.strategyId} ${if (exp.parentStrategies.isNotBlank()) "• Hybrid from: ${exp.parentStrategies}" else ""}", style = MaterialTheme.typography.bodySmall, color = NexoraViolet)

                    if (!exp.isSuccess && exp.failureReason.isNotBlank()) {
                        Text("Failure Reason:", style = MaterialTheme.typography.labelSmall, color = NexoraRose)
                        Text(exp.failureReason, style = MaterialTheme.typography.bodySmall, color = NexoraRose)
                    }

                    if (exp.memoryVerificationJson.isNotBlank()) {
                        Text("Memory Persistence Report:", style = MaterialTheme.typography.labelSmall, color = NexoraEmerald)
                        Text(
                            text = exp.memoryVerificationJson,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedExperiment = null }) {
                    Text("Close", color = NexoraCyan)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun ExperimentItemCard(
    experiment: ExperimentEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(
                1.dp,
                when (experiment.verificationStatus) {
                    "SUCCESS" -> NexoraEmerald.copy(alpha = 0.4f)
                    "PARTIAL" -> NexoraAmber.copy(alpha = 0.4f)
                    "NOT_VERIFIED" -> NexoraViolet.copy(alpha = 0.4f)
                    else -> NexoraRose.copy(alpha = 0.4f)
                },
                RoundedCornerShape(14.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp)
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
                Text(
                    text = experiment.experimentId,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = NexoraCyan
                )
                StatusBadge(
                    text = "${experiment.verificationStatus} (${experiment.evaluationScore}%)",
                    color = when (experiment.verificationStatus) {
                        "SUCCESS" -> NexoraEmerald
                        "PARTIAL" -> NexoraAmber
                        "NOT_VERIFIED" -> NexoraViolet
                        else -> NexoraRose
                    }
                )
            }

            Text(
                text = experiment.goal,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            // Prominent observable answer display
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Result:",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = experiment.finalHumanReadableAnswer.ifBlank { experiment.actualResult },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (experiment.isSuccess) NexoraEmerald else NexoraRose
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Strategy: ${experiment.strategyId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NexoraViolet
                )
                Text(
                    text = "Attempt #${experiment.attemptNumber}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
        }
    }
}
