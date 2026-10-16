package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.neural.HardwareBenchmarkEngine
import com.example.neural.LocalNeuralTrainer
import com.example.neural.NdkNeuralAccelerator
import com.example.ui.ChatScreen
import com.example.ui.DatasetManagerScreen
import com.example.ui.HardwareBenchmarkScreen
import com.example.ui.ModelStudioScreen
import com.example.ui.NeuralTrainingScreen
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceCard
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeuralGreen
import com.example.ui.theme.NexoraTheme
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexoraTheme {
                MainApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val coroutineScope = rememberCoroutineScope()
    val trainer = remember { LocalNeuralTrainer(coroutineScope) }
    val benchmarkEngine = remember { HardwareBenchmarkEngine() }

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    // Predictive / Custom back handling
    BackHandler(enabled = selectedTabIndex != 0) {
        selectedTabIndex = 0
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBackground),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Text(
                                text = "Nexora",
                                fontWeight = FontWeight.ExtraBold,
                                color = ElectricCyan,
                                fontSize = 19.sp
                            )
                            Text(
                                text = " • Studio On-Device",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }

                        // Badge showing NDK Status
                        Text(
                            text = if (NdkNeuralAccelerator.isNdkAvailable) "NDK 27 C++" else "JVM Engine",
                            color = if (NdkNeuralAccelerator.isNdkAvailable) NeuralGreen else ElectricCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 12.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CyberSurface,
                    titleContentColor = TextPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CyberSurfaceCard,
                contentColor = TextPrimary,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    icon = { Icon(Icons.Default.Bolt, contentDescription = "Training") },
                    label = { Text("Training", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricCyan,
                        selectedTextColor = ElectricCyan,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = CyberSurface
                    ),
                    modifier = Modifier.testTag("nav_tab_training")
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat") },
                    label = { Text("Chat", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonPurple,
                        selectedTextColor = NeonPurple,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = CyberSurface
                    ),
                    modifier = Modifier.testTag("nav_tab_chat")
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    icon = { Icon(Icons.Default.Dataset, contentDescription = "Dataset") },
                    label = { Text("Dataset", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeuralGreen,
                        selectedTextColor = NeuralGreen,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = CyberSurface
                    ),
                    modifier = Modifier.testTag("nav_tab_dataset")
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 3,
                    onClick = { selectedTabIndex = 3 },
                    icon = { Icon(Icons.Default.Architecture, contentDescription = "Studio") },
                    label = { Text("Studio", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricCyan,
                        selectedTextColor = ElectricCyan,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = CyberSurface
                    ),
                    modifier = Modifier.testTag("nav_tab_studio")
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 4,
                    onClick = { selectedTabIndex = 4 },
                    icon = { Icon(Icons.Default.Speed, contentDescription = "Benchmark") },
                    label = { Text("Benchmark", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TextPrimary,
                        selectedTextColor = TextPrimary,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = CyberSurface
                    ),
                    modifier = Modifier.testTag("nav_tab_benchmark")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(CyberBackground),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
            ) {
                when (selectedTabIndex) {
                    0 -> NeuralTrainingScreen(
                        trainer = trainer,
                        onNavigateToInference = { selectedTabIndex = 1 }
                    )
                    1 -> ChatScreen(
                        trainer = trainer,
                        onNavigateToTraining = { selectedTabIndex = 0 }
                    )
                    2 -> DatasetManagerScreen(
                        trainer = trainer,
                        onDatasetChanged = { /* synced */ }
                    )
                    3 -> ModelStudioScreen(
                        trainer = trainer,
                        onNavigateToChat = { selectedTabIndex = 1 }
                    )
                    4 -> HardwareBenchmarkScreen(
                        benchmarkEngine = benchmarkEngine
                    )
                }
            }
        }
    }
}
