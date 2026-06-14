package com.nexus.browser.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Light Theme Colors
object LightColorPalette {
    val primary = Color(0xFF5B7FFF)
    val primaryVariant = Color(0xFF4A68E8)
    val secondary = Color(0xFF03DAC6)
    val background = Color(0xFFFAFAFA)
    val surface = Color.White
    val error = Color(0xFFB00020)
    val textPrimary = Color(0xFF2D3748)
    val textSecondary = Color(0xFF718096)
    val divider = Color(0xFFE2E8F0)
    
    // Gradient Colors
    val gradientStart = Color(0xFFF0F4FF)
    val gradientEnd = Color(0xFFFFE6F0)
}

// Dark Theme Colors
object DarkColorPalette {
    val primary = Color(0xFF7B9FFF)
    val primaryVariant = Color(0xFF5B7FFF)
    val secondary = Color(0xFF03DAC6)
    val background = Color(0xFF1a1a1a)
    val surface = Color(0xFF2D3748)
    val error = Color(0xFFCF6679)
    val textPrimary = Color(0xFFF7FAFC)
    val textSecondary = Color(0xFFCBD5E0)
    val divider = Color(0xFF4A5568)
    
    // Gradient Colors
    val gradientStart = Color(0xFF1F2937)
    val gradientEnd = Color(0xFF2D3748)
}

@Composable
fun lightColorScheme() = lightColorScheme(
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

// Spacing Constants
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

// Corner Radius Constants
object Corners {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

// Elevation Constants
object Elevations {
    val none = 0.dp
    val xs = 1.dp
    val sm = 2.dp
    val md = 4.dp
    val lg = 8.dp
    val xl = 16.dp
}
