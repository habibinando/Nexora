package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.neural.AuthUiState
import com.example.neural.AuthViewModel
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
import com.example.ui.theme.CyberSurfaceVariant
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
fun MainApp(
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val trainer = remember { LocalNeuralTrainer(coroutineScope) }
    val benchmarkEngine = remember { HardwareBenchmarkEngine() }

    val authState by authViewModel.uiState.collectAsState()
    var showCloudDialog by remember { mutableStateOf(false) }

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

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            // Badge showing NDK Status
                            Text(
                                text = if (NdkNeuralAccelerator.isNdkAvailable) "NDK 27 C++" else "JVM Engine",
                                color = if (NdkNeuralAccelerator.isNdkAvailable) NeuralGreen else ElectricCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 4.dp)
                            )

                            // Cloud Firestore sync action icon
                            IconButton(
                                onClick = { showCloudDialog = true },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("firestore_cloud_status_button")
                            ) {
                                val isAuth = authState is AuthUiState.Authenticated
                                Icon(
                                    imageVector = if (isAuth) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                                    contentDescription = "Status Cloud Firestore",
                                    tint = if (isAuth) NeuralGreen else TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
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

        if (showCloudDialog) {
            CloudFirestoreDialog(
                authState = authState,
                onSignIn = { authViewModel.signInWithGoogle(context) },
                onSignOut = { authViewModel.signOut() },
                onDismiss = { showCloudDialog = false }
            )
        }
    }
}

@Composable
private fun CloudFirestoreDialog(
    authState: AuthUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val databaseId = remember { context.getString(R.string.firestore_database_id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Cloud Firestore Database",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CyberSurfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Database ID: $databaseId",
                            fontSize = 12.sp,
                            color = ElectricCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Status: Terhubung & Siap Sinkronisasi",
                            fontSize = 12.sp,
                            color = NeuralGreen
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Koleksi: /users/{userId}/datasets, /users/{userId}/training_runs, /users/{userId}/checkpoints, /users/{userId}/chat_messages",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                when (authState) {
                    is AuthUiState.Authenticated -> {
                        val user = authState.user
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CyberSurface),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = NeuralGreen,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = user.displayName ?: "Pengguna Terotentikasi",
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 14.sp
                                    )
                                }
                                user.email?.let { email ->
                                    Text(
                                        text = email,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Text(
                                    text = "UID: ${user.uid.take(12)}...",
                                    color = TextTertiary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }

                        Button(
                            onClick = onSignOut,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sign_out_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberSurfaceVariant)
                        ) {
                            Text("Keluar dari Akun", color = TextPrimary)
                        }
                    }

                    is AuthUiState.Loading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = ElectricCyan, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Menghubungkan ke Google...", color = TextSecondary, fontSize = 13.sp)
                        }
                    }

                    is AuthUiState.Error -> {
                        Text(
                            text = "Error: ${authState.message}",
                            color = androidx.compose.ui.graphics.Color(0xFFEF4444),
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = onSignIn,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("google_signin_retry_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                        ) {
                            Icon(Icons.Default.Login, contentDescription = null, tint = CyberBackground)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Coba Sign-in Google Lagi", color = CyberBackground, fontWeight = FontWeight.Bold)
                        }
                    }

                    is AuthUiState.Idle -> {
                        Text(
                            text = "Masuk dengan akun Google via Credential Manager untuk mengaktifkan backup cloud dan sinkronisasi real-time dataset, riwayat training, dan checkpoint model Anda.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )

                        Button(
                            onClick = onSignIn,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("google_signin_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                        ) {
                            Icon(Icons.Default.Login, contentDescription = null, tint = CyberBackground)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign in with Google", color = CyberBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup", color = ElectricCyan)
            }
        },
        containerColor = CyberSurfaceCard,
        shape = RoundedCornerShape(16.dp)
    )
}
