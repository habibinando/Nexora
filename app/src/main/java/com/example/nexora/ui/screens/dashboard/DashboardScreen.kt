package com.example.nexora.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nexora.ui.components.MetricCard
import com.example.nexora.ui.components.StatusBadge
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.ui.theme.*

@Composable
fun DashboardScreen(
    viewModel: NexoraViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeModel by viewModel.activeModelInfo.collectAsStateWithLifecycle()
    val deviceMem by viewModel.deviceMemory.collectAsStateWithLifecycle()
    val memories by viewModel.allMemories.collectAsStateWithLifecycle()
    val experiments by viewModel.allExperiments.collectAsStateWithLifecycle()
    val strategies by viewModel.allStrategies.collectAsStateWithLifecycle()
    val tools by viewModel.toolsState.collectAsStateWithLifecycle()
    val modules by viewModel.adapterModules.collectAsStateWithLifecycle()
    val versions by viewModel.modelVersions.collectAsStateWithLifecycle()
    val isOffline by viewModel.offlineEnforced.collectAsStateWithLifecycle()

    val successfulExp = experiments.count { it.isSuccess }
    val failedExp = experiments.count { !it.isSuccess }
    val activeVersion = versions.firstOrNull { it.isCurrentActive }?.versionId ?: "v1.0"

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // Hero Header
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NexoraCyan.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(NexoraCyanDark.copy(alpha = 0.35f), Color.Transparent)
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Nexora",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Black,
                                        color = TextPrimary
                                    )
                                    StatusBadge(text = "v4.0", color = NexoraEmerald)
                                }
                                Text(
                                    text = "Autonomous Local-First Intelligence Core",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NexoraCyan
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            StatusBadge(
                                text = if (isOffline) "OFFLINE SECURE" else "HYBRID",
                                color = if (isOffline) NexoraCyan else NexoraEmerald
                            )
                        }

                        HorizontalDivider(color = DarkOutline.copy(alpha = 0.5f), thickness = 1.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Active Model", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(
                                    text = activeModel.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Model Version", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(
                                    text = activeVersion,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = NexoraViolet,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Quantization / Size", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(
                                    text = "${activeModel.quantization} • ${activeModel.sizeMb}MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Context Window", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(
                                    text = "${activeModel.contextLength} Tokens",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        // Hardware Accelerator State Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Akselerasi Hardware", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                val accelLabel = if (activeModel.isGpuEnabled) "Vulkan GPU Compute Engine" else "CPU SIMD Vectorized (${activeModel.cpuThreads} Threads)"
                                Text(
                                    text = accelLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (activeModel.isGpuEnabled) NexoraEmerald else NexoraCyan,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            StatusBadge(
                                text = if (activeModel.isGpuEnabled) "GPU VULKAN ON" else "CPU NEON",
                                color = if (activeModel.isGpuEnabled) NexoraEmerald else NexoraCyan
                            )
                        }

                        // RAM Bar
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("RAM Usage", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(
                                    "${activeModel.requiredRamMb}MB Req / ${deviceMem.availableRamMb}MB Free",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (deviceMem.isLowMemory) NexoraRose else NexoraEmerald
                                )
                            }
                            val progress = if (deviceMem.totalRamMb > 0) {
                                (activeModel.requiredRamMb.toFloat() / deviceMem.totalRamMb.toFloat()).coerceIn(0.05f, 1f)
                            } else 0.25f
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = NexoraCyan,
                                trackColor = DarkSurfaceHighlight
                            )
                        }
                    }
                }
            }
        }

        // Section Title: Navigation Hub
        item {
            Text(
                text = "System Navigation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        // 8 Grid Navigation Buttons Required by Section 14
        item {
            val navItems = listOf(
                NavButtonData("Chat", Icons.AutoMirrored.Filled.Chat, NexoraCyan, "chat", "Live streaming inference"),
                NavButtonData("Memory", Icons.Default.Psychology, NexoraViolet, "memory", "${memories.size} persistent memories"),
                NavButtonData("Experiments", Icons.Default.Science, NexoraEmerald, "experiments", "$successfulExp passed • $failedExp failed"),
                NavButtonData("Strategies", Icons.Default.Lightbulb, NexoraAmber, "strategies", "${strategies.size} in library"),
                NavButtonData("Tools", Icons.Default.Build, NexoraCyan, "tools", "${tools.size} registered"),
                NavButtonData("Models", Icons.Default.Memory, NexoraViolet, "models", "Replaceable local weights"),
                NavButtonData("Training", Icons.Default.ModelTraining, NexoraEmerald, "training", "${modules.size} adapters"),
                NavButtonData("Settings", Icons.Default.Settings, TextSecondary, "settings", "Offline & retry controls")
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (chunk in navItems.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (item in chunk) {
                            DashboardNavCard(
                                item = item,
                                onClick = { onNavigate(item.route) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // System Telemetry Metrics Grid
        item {
            Text(
                text = "Autonomous Intelligence State",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    title = "Memories",
                    value = memories.size.toString(),
                    subtitle = "Survives restarts",
                    icon = Icons.Default.Storage,
                    accentColor = NexoraViolet,
                    onClick = { onNavigate("memory") },
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Experiments",
                    value = experiments.size.toString(),
                    subtitle = "$successfulExp Success / $failedExp Failed",
                    icon = Icons.Default.AutoMode,
                    accentColor = NexoraEmerald,
                    onClick = { onNavigate("experiments") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    title = "Strategies",
                    value = strategies.size.toString(),
                    subtitle = "Library Heuristics",
                    icon = Icons.Default.AccountTree,
                    accentColor = NexoraAmber,
                    onClick = { onNavigate("strategies") },
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Modules & Adapters",
                    value = modules.count { it.isEnabled }.toString(),
                    subtitle = "${modules.size} Installed LoRAs",
                    icon = Icons.Default.Extension,
                    accentColor = NexoraCyan,
                    onClick = { onNavigate("training") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Recent Learning Feed
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Experiments & Lessons",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                TextButton(onClick = { onNavigate("experiments") }) {
                    Text("View All", color = NexoraCyan)
                }
            }
        }

        if (experiments.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No experiments executed yet. Launch one from Chat or Experiments tab!",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        } else {
            items(experiments.take(3)) { exp ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkOutline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .clickable { onNavigate("experiments") },
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = exp.experimentId,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = NexoraCyan
                            )
                            StatusBadge(
                                text = if (exp.isSuccess) "PASSED (${exp.evaluationScore}%)" else "FAILED",
                                color = if (exp.isSuccess) NexoraEmerald else NexoraRose
                            )
                        }
                        Text(
                            text = exp.goal,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 2
                        )
                        if (exp.lessonLearned.isNotBlank()) {
                            Text(
                                text = "Learned: ${exp.lessonLearned}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

data class NavButtonData(
    val title: String,
    val icon: ImageVector,
    val color: Color,
    val route: String,
    val subtitle: String
)

@Composable
fun DashboardNavCard(
    item: NavButtonData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .testTag("nav_btn_${item.route}")
            .clickable { onClick() }
            .border(1.dp, DarkOutline.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(item.color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    tint = item.color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}
