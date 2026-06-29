package com.nexus.browser.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Light Color Palette
object LightColorPalette {
    val primary = Color(0xFF1A73E8)
    val secondary = Color(0xFFFF9800)
    val error = Color(0xFFD32F2F)
    val background = Color(0xFFFFFFFF)
    val surface = Color(0xFFFFFFFF)
    val textPrimary = Color(0xFF202124)
    val divider = Color(0xFFBDBDBD)
}

// Dark Color Palette
object DarkColorPalette {
    val primary = Color(0xFF6BA3FF)
    val secondary = Color(0xFFFFB74D)
    val error = Color(0xFFEF5350)
    val background = Color(0xFF121212)
    val surface = Color(0xFF1E1E1E)
    val textPrimary = Color(0xFFEDEDED)
    val divider = Color(0xFF424242)
}

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

    fun backgroundFor(isDark: Boolean) = if (isDark) backgroundGradientDark else backgroundGradient
    
    // Dark mode gradient
    private val gradientDarkBlue = Color(0xFF0D1B2A)
    private val gradientDarkPurple = Color(0xFF2C0E35)
    private val gradientDarkGray = Color(0xFF1A1A1A)
    
    val backgroundGradientDark = Brush.linearGradient(
        colors = listOf(gradientDarkBlue, gradientDarkPurple, gradientDarkGray),
        start = Offset(0f, 0f),
        end = Offset(1000f, 1000f)
    )
    
    // Glassmorphism effects
    fun glassSurface(alpha: Float = 0.7f) = Color.White.copy(alpha = alpha)
    fun glassSurfaceDark(alpha: Float = 0.7f) = Color(0xFF1E1E1E).copy(alpha = alpha)
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
        darkColorScheme(
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
    } else {
        lightColorScheme(
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
    }
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}


object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}
