package com.nexus.browser.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Nexus Browser Color Tokens
object NexusColors {
    // Primary colors
    val primary = Color(0xFF1A73E8)
    val primaryVariant = Color(0xFF0D47A1)
    val secondary = Color(0xFFFF9800)
    
    // Surface/elevation
    val surface = Color.White
    val surfaceVariant = Color.White.copy(alpha = 0.7f)
    val surfaceDark = Color(0xFF1E1E1E)
    
    // Text & icons
    val textPrimary = Color(0xFF202124)
    val textSecondary = Color(0xFF5F6368)
    val textTertiary = Color(0xFF9AA0A6)
    
    // Background gradients
    private val gradientBlue = Color(0xFFE3F2FD)
    private val gradientPink = Color(0xFFFCE4EC)
    private val gradientWhite = Color(0xFFFFFFFF)
    
    val backgroundGradient = Brush.linearGradient(
        colors = listOf(gradientBlue, gradientPink, gradientWhite),
        start = Offset(0f, 0f),
        end = Offset(1000f, 1000f)
    )
    
    // Glassmorphism effects
    fun glassSurface(alpha: Float = 0.7f) = Color.White.copy(alpha = alpha)
    val glassBorder = Color.White.copy(alpha = 0.8f)
}

// Corner radius constants (matches spec)
object Corners {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val xlarge = 32.dp
    
    // Special cases
    val searchBar = 24.dp
    val bottomNav = 24.dp
    val bottomSheet = 32.dp
}

@Composable
fun NexusTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        // TODO: Implement dark theme
        darkColorScheme()
    } else {
        lightColorScheme(
            primary = NexusColors.primary,
            onPrimary = Color.White,
            primaryContainer = NexusColors.primaryVariant,
            secondary = NexusColors.secondary,
            onSecondary = Color.Black,
            background = Color.Transparent,
            surface = NexusColors.surface,
            onSurface = NexusColors.textPrimary,
            surfaceVariant = NexusColors.surfaceVariant
        )
    }
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
private fun lightColorScheme() = lightColorScheme(
    primary = LightColorPalette.primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF0FF),
    onPrimaryContainer = LightColorPalette.primary,
    secondary = LightColorPalette.secondary,
    onSecondary = Color.White,
    error = LightColorPalette.error,
    onError = Color.White,
    background = LightColorPalette.background,
    onBackground = LightColorPalette.textPrimary,
    surface = LightColorPalette.surface,
    onSurface = LightColorPalette.textPrimary,
    outline = LightColorPalette.divider
)

@Composable
fun darkColorScheme() = darkColorScheme(
    primary = DarkColorPalette.primary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3D4E8A),
    onPrimaryContainer = DarkColorPalette.primary,
    secondary = DarkColorPalette.secondary,
    onSecondary = Color.Black,
    error = DarkColorPalette.error,
    onError = Color.Black,
    background = DarkColorPalette.background,
    onBackground = DarkColorPalette.textPrimary,
    surface = DarkColorPalette.surface,
    onSurface = DarkColorPalette.textPrimary,
    outline = DarkColorPalette.divider
)

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
