package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nexora.ui.screens.chat.ChatScreen
import com.example.nexora.ui.screens.dashboard.DashboardScreen
import com.example.nexora.ui.screens.memory.MemoryScreen
import com.example.nexora.ui.screens.models.ModelsScreen
import com.example.nexora.ui.screens.settings.SettingsScreen
import com.example.nexora.ui.screens.tools.ToolsScreen
import com.example.nexora.ui.screens.training.TrainingScreen
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.ui.theme.*

enum class NexoraScreen(val title: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Default.Dashboard),
    Chat("Chat", Icons.Default.Chat),
    Training("Training", Icons.Default.ModelTraining),
    Memory("Memory", Icons.Default.Psychology),
    Tools("Tools", Icons.Default.Build),
    Models("Models", Icons.Default.Memory),
    Settings("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: NexoraViewModel = viewModel()
                var currentScreen by remember { mutableStateOf(NexoraScreen.Dashboard) }

                BackHandler(enabled = currentScreen != NexoraScreen.Dashboard) {
                    currentScreen = NexoraScreen.Dashboard
                }

                val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                val isImeVisible = imeBottom > 0.dp

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.statusBars,
                    bottomBar = {
                        // Hide bottom bar when soft keyboard is active in Chat so chat input stays attached to keyboard
                        if (!isImeVisible || currentScreen != NexoraScreen.Chat) {
                            NexoraBottomNavBar(
                                currentScreen = currentScreen,
                                onScreenSelected = { currentScreen = it }
                            )
                        }
                    },
                    containerColor = DarkBackground
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                top = innerPadding.calculateTopPadding(),
                                bottom = if (isImeVisible && currentScreen == NexoraScreen.Chat) 0.dp else innerPadding.calculateBottomPadding()
                            )
                    ) {
                        when (currentScreen) {
                            NexoraScreen.Dashboard -> DashboardScreen(
                                viewModel = viewModel,
                                onNavigate = { route ->
                                    when (route) {
                                        "chat" -> currentScreen = NexoraScreen.Chat
                                        "memory" -> currentScreen = NexoraScreen.Memory
                                        "tools" -> currentScreen = NexoraScreen.Tools
                                        "models" -> currentScreen = NexoraScreen.Models
                                        "training" -> currentScreen = NexoraScreen.Training
                                        "settings" -> currentScreen = NexoraScreen.Settings
                                    }
                                }
                            )
                            NexoraScreen.Chat -> ChatScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = NexoraScreen.Dashboard },
                                onNavigateToExperiments = { currentScreen = NexoraScreen.Training },
                                onNavigateToSettings = { currentScreen = NexoraScreen.Settings }
                            )
                            NexoraScreen.Training -> TrainingScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = NexoraScreen.Dashboard }
                            )
                            NexoraScreen.Memory -> MemoryScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = NexoraScreen.Dashboard }
                            )
                            NexoraScreen.Tools -> ToolsScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = NexoraScreen.Dashboard }
                            )
                            NexoraScreen.Models -> ModelsScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = NexoraScreen.Dashboard }
                            )
                            NexoraScreen.Settings -> SettingsScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = NexoraScreen.Dashboard }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NexoraBottomNavBar(
    currentScreen: NexoraScreen,
    onScreenSelected: (NexoraScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryTabs = remember {
        listOf(
            NexoraScreen.Dashboard,
            NexoraScreen.Chat,
            NexoraScreen.Training,
            NexoraScreen.Models,
            NexoraScreen.Settings
        )
    }

    Surface(
        color = DarkSurface,
        tonalElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            primaryTabs.forEach { screen ->
                val selected = currentScreen == screen

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 28.dp),
                            onClick = { onScreenSelected(screen) }
                        )
                        .padding(vertical = 4.dp)
                        .testTag("nav_tab_${screen.name.lowercase()}")
                ) {
                    Box(
                        modifier = Modifier
                            .height(28.dp)
                            .widthIn(min = 44.dp, max = 54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) NexoraCyan else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = screen.title,
                            tint = if (selected) DarkBackground else TextSecondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = screen.title,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            letterSpacing = (-0.3).sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (selected) NexoraCyan else TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1.dp)
                    )
                }
            }
        }
    }
}
