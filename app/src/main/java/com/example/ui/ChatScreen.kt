package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.neural.ChatMessage
import com.example.neural.ChatSender
import com.example.neural.LocalNeuralTrainer
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceCard
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeuralGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    trainer: LocalNeuralTrainer,
    onNavigateToTraining: () -> Unit
) {
    val messages by trainer.chatMessages.collectAsState()
    val isGenerating by trainer.isChatGenerating.collectAsState()
    val telemetry by trainer.telemetry.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var temperature by remember { mutableFloatStateOf(0.7f) }
    var maxTokens by remember { mutableIntStateOf(100) }
    var showSettingsDrawer by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val quickPrompts = listOf(
        "Halo Nexora!",
        "Bagaimana cara kerjamu?",
        "Jelaskan arsitektur saraf on-device",
        "Apa itu BPTT?",
        "Selamat pagi Nexora"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top Header Banner with training status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (telemetry.latestLoss != null) NeuralGreen else ElectricCyan,
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Nexora Neural Chat",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (telemetry.latestLoss != null) {
                                "Loss: ${String.format(Locale.US, "%.3f", telemetry.latestLoss)} • ${telemetry.totalParameters ?: 0} params"
                            } else {
                                "Model inisial (belum dilatih) • ${telemetry.totalParameters ?: 0} params"
                            },
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = { showSettingsDrawer = !showSettingsDrawer },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Pengaturan Inferensi",
                            tint = if (showSettingsDrawer) ElectricCyan else TextSecondary
                        )
                    }
                    IconButton(
                        onClick = { trainer.clearChat() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Bersihkan Obrolan",
                            tint = TextSecondary
                        )
                    }
                }
            }
        }

        // Optional settings panel
        if (showSettingsDrawer) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Temperature: ${String.format(Locale.US, "%.2f", temperature)}", color = TextPrimary, fontSize = 12.sp)
                        Text(if (temperature < 0.5f) "Deterministik" else "Kreatif", color = ElectricCyan, fontSize = 12.sp)
                    }
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0.2f..1.5f,
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricCyan,
                            activeTrackColor = ElectricCyan,
                            inactiveTrackColor = CyberSurfaceCard
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Maksimal Karakter: $maxTokens", color = TextPrimary, fontSize = 12.sp)
                        Text("Panjang respons", color = TextSecondary, fontSize = 12.sp)
                    }
                    Slider(
                        value = maxTokens.toFloat(),
                        onValueChange = { maxTokens = it.toInt() },
                        valueRange = 20f..250f,
                        steps = 22,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonPurple,
                            activeTrackColor = NeonPurple,
                            inactiveTrackColor = CyberSurfaceCard
                        )
                    )
                }
            }
        }

        // Untrained model guidance banner
        if (telemetry.latestLoss == null || (telemetry.latestLoss ?: 4.0f) > 3.0f) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                border = ButtonDefaults.outlinedButtonBorder
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = "Info", tint = ElectricCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Bobot model belum banyak dilatih (loss masih tinggi). Karakter keluaran mungkin belum terstruktur. Latih model di tab Training.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = onNavigateToTraining,
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black)
                    ) {
                        Text("Latih", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Chat message list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatMessageItem(msg)
            }

            if (isGenerating) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            color = ElectricCyan,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Nexora sedang menggenerasi karakter secara on-device...",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick prompts chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(quickPrompts) { prompt ->
                FilterChip(
                    selected = false,
                    onClick = {
                        inputText = prompt
                    },
                    label = { Text(prompt, fontSize = 11.sp, color = TextPrimary) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = CyberSurfaceCard
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = CyberSurface,
                        enabled = true,
                        selected = false
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Input Box
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Ketik pesan atau prompt untuk Nexora...", color = TextTertiary, fontSize = 13.sp) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input_field"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CyberSurface,
                    unfocusedContainerColor = CyberSurface,
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = CyberSurfaceCard,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(24.dp),
                maxLines = 3
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (inputText.isNotBlank() && !isGenerating) {
                        val textToSend = inputText
                        inputText = ""
                        trainer.sendChatMessage(
                            userText = textToSend,
                            temperature = temperature,
                            maxTokens = maxTokens
                        )
                    }
                },
                enabled = inputText.isNotBlank() && !isGenerating,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        brush = Brush.horizontalGradient(listOf(ElectricCyan, NeonPurple)),
                        shape = CircleShape
                    )
                    .testTag("chat_send_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Kirim Pesan",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage) {
    val isUser = message.sender == ChatSender.USER
    val isSystem = message.sender == ChatSender.SYSTEM

    val alignment = when {
        isUser -> Alignment.CenterEnd
        isSystem -> Alignment.Center
        else -> Alignment.CenterStart
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        if (isSystem) {
            Text(
                text = message.text,
                color = TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(CyberSurfaceCard, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        } else {
            Column(
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Card(
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isUser) CyberSurfaceCard else CyberSurface
                    ),
                    border = if (!isUser) {
                        ButtonDefaults.outlinedButtonBorder
                    } else null
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (isUser) Icons.Default.Bolt else Icons.Default.Psychology,
                                contentDescription = null,
                                tint = if (isUser) ElectricCyan else NeonPurple,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isUser) "Anda" else "Nexora AI",
                                fontWeight = FontWeight.Bold,
                                color = if (isUser) ElectricCyan else NeonPurple,
                                fontSize = 11.sp
                            )
                        }

                        Text(
                            text = message.text,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = if (!isUser) FontFamily.Default else FontFamily.Default
                        )
                    }
                }

                // Telemetry footer for assistant responses
                if (!isUser && message.tokensGenerated > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(
                            text = "⚡ ${message.latencyMs} ms",
                            color = TextTertiary,
                            fontSize = 10.sp
                        )
                        Text(
                            text = " • ${String.format(Locale.US, "%.1f", message.tokensPerSec)} char/s",
                            color = TextTertiary,
                            fontSize = 10.sp
                        )
                        Text(
                            text = " • ${message.tokensGenerated} chars",
                            color = TextTertiary,
                            fontSize = 10.sp
                        )
                        Text(
                            text = if (message.isNdkUsed) " • NDK C++20" else " • JVM",
                            color = if (message.isNdkUsed) NeuralGreen else ElectricCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
