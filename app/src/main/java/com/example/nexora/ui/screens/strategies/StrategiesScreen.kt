package com.example.nexora.ui.screens.strategies

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nexora.model.database.StrategyEntity
import com.example.nexora.ui.components.NexoraHeader
import com.example.nexora.ui.components.StatusBadge
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.ui.theme.*
import org.json.JSONArray

@Composable
fun StrategiesScreen(
    viewModel: NexoraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val strategies by viewModel.allStrategies.collectAsStateWithLifecycle()
    val activeModel by viewModel.activeModelInfo.collectAsStateWithLifecycle()
    val isOffline by viewModel.offlineEnforced.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedStrategy by remember { mutableStateOf<StrategyEntity?>(null) }

    val filteredStrategies = when (selectedTab) {
        1 -> strategies.filter { it.isSuccessful == true }
        2 -> strategies.filter { it.isSuccessful == false }
        3 -> strategies.filter { it.parentStrategies.isNotBlank() } // Hybrid / Synthesized
        else -> strategies
    }

    Scaffold(
        topBar = {
            NexoraHeader(
                title = "Strategy Library",
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
            // Tab Selector
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurface,
                contentColor = NexoraCyan,
                edgePadding = 12.dp
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("All (${strategies.size})", fontSize = 12.sp, maxLines = 1) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Proven (${strategies.count { it.isSuccessful == true }})", fontSize = 12.sp, maxLines = 1) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Failed (${strategies.count { it.isSuccessful == false }})", fontSize = 12.sp, maxLines = 1) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Synthesized (${strategies.count { it.parentStrategies.isNotBlank() }})", fontSize = 12.sp, maxLines = 1) }
                )
            }

            // Strategy Cards List
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                if (filteredStrategies.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No strategies in this category yet.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    items(filteredStrategies) { strat ->
                        StrategyCard(
                            strategy = strat,
                            onClick = { selectedStrategy = strat }
                        )
                    }
                }
            }
        }
    }

    // Detail Modal
    selectedStrategy?.let { strat ->
        val stepsList = try {
            val arr = JSONArray(strat.stepsJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            listOf(strat.stepsJson)
        }

        AlertDialog(
            onDismissRequest = { selectedStrategy = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strat.strategyId, fontWeight = FontWeight.Bold, color = TextPrimary)
                    StatusBadge(
                        text = when (strat.isSuccessful) {
                            true -> "PROVEN SUCCESS"
                            false -> "FAILED POST-MORTEM"
                            else -> "CANDIDATE"
                        },
                        color = when (strat.isSuccessful) {
                            true -> NexoraEmerald
                            false -> NexoraRose
                            else -> NexoraCyan
                        }
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Goal:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(strat.goal, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)

                    if (strat.parentStrategies.isNotBlank()) {
                        Text("Parent Lineage (Synthesized):", style = MaterialTheme.typography.labelSmall, color = NexoraAmber)
                        Text(strat.parentStrategies, style = MaterialTheme.typography.bodySmall, color = NexoraAmber)
                    }

                    Text("Risk Level: ${strat.riskOfFailure}", style = MaterialTheme.typography.labelSmall, color = if (strat.riskOfFailure == "HIGH") NexoraRose else NexoraCyan)

                    Text("Required Tools:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(strat.requiredTools.ifBlank { "None (Local Reasoning Only)" }, style = MaterialTheme.typography.bodySmall, color = NexoraViolet)

                    Text("Planned Execution Steps:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    stepsList.forEachIndexed { i, step ->
                        Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    }

                    if (strat.reasoningSummary.isNotBlank()) {
                        Text("Reasoning Summary:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text(strat.reasoningSummary, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedStrategy = null }) {
                    Text("Close", color = NexoraCyan)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun StrategyCard(
    strategy: StrategyEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(1.dp, DarkOutline.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
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
                    text = strategy.strategyId,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = NexoraCyan
                )
                StatusBadge(
                    text = when (strategy.isSuccessful) {
                        true -> "PROVEN"
                        false -> "FAILED"
                        else -> "CANDIDATE"
                    },
                    color = when (strategy.isSuccessful) {
                        true -> NexoraEmerald
                        false -> NexoraRose
                        else -> NexoraCyan
                    }
                )
            }

            Text(
                text = strategy.goal,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            if (strategy.parentStrategies.isNotBlank()) {
                Text(
                    text = "Synthesized from: ${strategy.parentStrategies}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NexoraAmber
                )
            }

            Text(
                text = "Tools: ${strategy.requiredTools.ifBlank { "Local Reasoning" }} • Risk: ${strategy.riskOfFailure}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}
