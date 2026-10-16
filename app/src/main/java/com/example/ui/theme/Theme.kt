package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NeuralDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color.Black,
    primaryContainer = DeepViolet,
    onPrimaryContainer = Color.White,
    secondary = NeonPurple,
    onSecondary = Color.White,
    secondaryContainer = CyberSurfaceVariant,
    onSecondaryContainer = ElectricCyan,
    tertiary = NeuralGreen,
    onTertiary = Color.Black,
    background = CyberBackground,
    onBackground = TextPrimary,
    surface = CyberSurface,
    onSurface = TextPrimary,
    surfaceVariant = CyberSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = DangerRed,
    onError = Color.White
)

@Composable
fun NexoraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NeuralDarkColorScheme,
        typography = Typography,
        content = content
    )
}

// Backward compatibility alias
@Composable
fun NeuralAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) = NexoraTheme(darkTheme, content)
