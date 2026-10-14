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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nexora.ui.screens.chat.ChatScreen
import com.example.nexora.ui.screens.dashboard.DashboardScreen
import com.example.nexora.ui.screens.dataset.DatasetScreen
import com.example.nexora.ui.screens.memory.MemoryScreen
import com.example.nexora.ui.screens.models.ModelsScreen
import com.example.nexora.ui.screens.pytorch.PyTorchImportScreen
import com.example.nexora.ui.screens.settings.SettingsScreen
import com.example.nexora.ui.screens.tools.ToolsScreen
import com.example.nexora.ui.screens.training.TrainingScreen
import com.example.nexora.ui.viewmodel.NexoraViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.launch

enum class NexoraScreen(val title: String, val icon: ImageVector, val subtitle: String) {
    Chat("Chat", Icons.Default.Chat, "Layar Utama AI On-Device"),
    Training("Training Neural", Icons.Default.ModelTraining, "Backpropagation & Loss Real"),
    PyTorchImport("Impor PyTorch (.pt)", Icons.Default.FileOpen, "Ekstrak Bobot & Lanjut Latih"),
    Dataset("Dataset Prompt-Reply", Icons.Default.Storage, "Kurasi Pasangan Latih"),
    Dashboard("Dashboard", Icons.Default.Dashboard, "Status & Metrik Hardware"),
    Models("Model Manager", Icons.Default.Memory, "GGUF & Arsitektur Lokal"),
    Memory("Memori Model", Icons.Default.Psychology, "SQLite Room Knowledge"),
    Tools("Perkakas Sistem", Icons.Default.Build, "Math & Python Sandbox"),
    Settings("Pengaturan", Icons.Default.Settings, "Konfigurasi 100% On-Device")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: NexoraViewModel = viewModel()
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                // Layar Utama default adalah Chat sesuai instruksi pengguna
                var currentScreen by remember { mutableStateOf(NexoraScreen.Chat) }

                val deviceMem by viewModel.deviceMemory.collectAsStateWithLifecycle()
                val activeModel by viewModel.activeModelInfo.collectAsStateWithLifecycle()

                // Back navigation: tutup drawer jika terbuka, atau kembali ke Chat jika di layar sub
                BackHandler(enabled = drawerState.isOpen || currentScreen != NexoraScreen.Chat) {
                    if (drawerState.isOpen) {
                        scope.launch { drawerState.close() }
                    } else {
                        currentScreen = NexoraScreen.Chat
                    }
                }

                val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                val isImeVisible = imeBottom > 0.dp

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = DarkSurface,
                            drawerContentColor = TextPrimary,
                            modifier = Modifier.width(310.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 16.dp, horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Header Drawer
                                Surface(
                                    color = DarkSurfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(16.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(NexoraCyan.copy(alpha = 0.2f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.AutoAwesome,
                                                    contentDescription = null,
                                                    tint = NexoraCyan,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                            Column {
                                                Text(
                                                    text = "Nexora Neural AI",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                )
                                                Text(
                                                    text = "100% On-Device • Bebas API Key",
                                                    fontSize = 11.sp,
                                                    color = NexoraCyan,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }

                                        HorizontalDivider(color = DarkOutline.copy(alpha = 0.4f))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "RAM HP Terpakai:",
                                                fontSize = 11.sp,
                                                color = TextSecondary
                                            )
                                            Text(
                                                text = "${(deviceMem.totalRamMb - deviceMem.availableRamMb) / 1024f} GB / ${deviceMem.totalRamMb / 1024f} GB",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "FITUR UTAMA NEURAL",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMuted,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )

                                // Primary items
                                listOf(
                                    NexoraScreen.Chat,
                                    NexoraScreen.Training,
                                    NexoraScreen.PyTorchImport,
                                    NexoraScreen.Dataset
                                ).forEach { screen ->
                                    val isSelected = currentScreen == screen
                                    NavigationDrawerItem(
                                        icon = {
                                            Icon(
                                                imageVector = screen.icon,
                                                contentDescription = screen.title,
                                                tint = if (isSelected) NexoraCyan else TextSecondary
                                            )
                                        },
                                        label = {
                                            Column {
                                                Text(
                                                    text = screen.title,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) NexoraCyan else TextPrimary,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    text = screen.subtitle,
                                                    fontSize = 10.sp,
                                                    color = TextMuted
                                                )
                                            }
                                        },
                                        selected = isSelected,
                                        onClick = {
                                            currentScreen = screen
                                            scope.launch { drawerState.close() }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = NexoraCyan.copy(alpha = 0.15f),
                                            unselectedContainerColor = Color.Transparent
                                        ),
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }

                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "SISTEM & PERKAKAS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMuted,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )

                                // Secondary items
                                listOf(
                                    NexoraScreen.Dashboard,
                                    NexoraScreen.Models,
                                    NexoraScreen.Memory,
                                    NexoraScreen.Tools,
                                    NexoraScreen.Settings
                                ).forEach { screen ->
                                    val isSelected = currentScreen == screen
                                    NavigationDrawerItem(
                                        icon = {
                                            Icon(
                                                imageVector = screen.icon,
                                                contentDescription = screen.title,
                                                tint = if (isSelected) NexoraCyan else TextSecondary
                                            )
                                        },
                                        label = {
                                            Column {
                                                Text(
                                                    text = screen.title,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) NexoraCyan else TextPrimary,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    text = screen.subtitle,
                                                    fontSize = 10.sp,
                                                    color = TextMuted
                                                )
                                            }
                                        },
                                        selected = isSelected,
                                        onClick = {
                                            currentScreen = screen
                                            scope.launch { drawerState.close() }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = NexoraCyan.copy(alpha = 0.15f),
                                            unselectedContainerColor = Color.Transparent
                                        ),
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        contentWindowInsets = WindowInsets.statusBars,
                        bottomBar = {
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
                                NexoraScreen.Chat -> ChatScreen(
                                    viewModel = viewModel,
                                    onBack = { /* Stay on Chat since it is the main screen */ },
                                    onNavigateToExperiments = { currentScreen = NexoraScreen.Training },
                                    onNavigateToSettings = { currentScreen = NexoraScreen.Settings },
                                    onNavigateToPyTorch = { currentScreen = NexoraScreen.PyTorchImport },
                                    onNavigateToDataset = { currentScreen = NexoraScreen.Dataset },
                                    onOpenMenu = { scope.launch { drawerState.open() } }
                                )
                                NexoraScreen.Training -> TrainingScreen(
                                    viewModel = viewModel,
                                    onBack = { currentScreen = NexoraScreen.Chat }
                                )
                                NexoraScreen.PyTorchImport -> PyTorchImportScreen(
                                    viewModel = viewModel,
                                    onBack = { currentScreen = NexoraScreen.Chat },
                                    onNavigateToTraining = { currentScreen = NexoraScreen.Training },
                                    onOpenMenu = { scope.launch { drawerState.open() } }
                                )
                                NexoraScreen.Dataset -> DatasetScreen(
                                    viewModel = viewModel,
                                    onBack = { currentScreen = NexoraScreen.Chat },
                                    onNavigateToTraining = { currentScreen = NexoraScreen.Training },
                                    onOpenMenu = { scope.launch { drawerState.open() } }
                                )
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
                                            "pytorch" -> currentScreen = NexoraScreen.PyTorchImport
                                            "dataset" -> currentScreen = NexoraScreen.Dataset
                                        }
                                    }
                                )
                                NexoraScreen.Memory -> MemoryScreen(
                                    viewModel = viewModel,
                                    onBack = { currentScreen = NexoraScreen.Chat }
                                )
                                NexoraScreen.Tools -> ToolsScreen(
                                    viewModel = viewModel,
                                    onBack = { currentScreen = NexoraScreen.Chat }
                                )
                                NexoraScreen.Models -> ModelsScreen(
                                    viewModel = viewModel,
                                    onBack = { currentScreen = NexoraScreen.Chat }
                                )
                                NexoraScreen.Settings -> SettingsScreen(
                                    viewModel = viewModel,
                                    onBack = { currentScreen = NexoraScreen.Chat }
                                )
                            }
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
            NexoraScreen.Chat,
            NexoraScreen.Training,
            NexoraScreen.PyTorchImport,
            NexoraScreen.Dataset,
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
