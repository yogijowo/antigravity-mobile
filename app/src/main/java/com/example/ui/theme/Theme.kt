package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = OrbitalCyan80,
    onPrimary = ObsidianBackground,
    primaryContainer = Color(0xFF0E3A5D),
    onPrimaryContainer = Color(0xFFD6EEFF),
    secondary = OrbitalBlue80,
    onSecondary = ObsidianBackground,
    secondaryContainer = Color(0xFF1E2B4A),
    onSecondaryContainer = Color(0xFFD9E6FF),
    tertiary = OrbitalMint80,
    onTertiary = ObsidianBackground,
    background = ObsidianBackground,
    onBackground = ObsidianOnSurface,
    surface = ObsidianSurface,
    onSurface = ObsidianOnSurface,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = ObsidianOnSurfaceVariant,
    outline = ObsidianOutline,
    error = VoiceActiveCoral,
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = OrbitalBlue40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0B2559),
    secondary = OrbitalCyan40,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF082F49),
    tertiary = OrbitalTeal40,
    onTertiary = Color.White,
    background = PorcelainBackground,
    onBackground = PorcelainOnSurface,
    surface = PorcelainSurface,
    onSurface = PorcelainOnSurface,
    surfaceVariant = PorcelainSurfaceVariant,
    onSurfaceVariant = PorcelainOnSurfaceVariant,
    outline = PorcelainOutline,
    error = VoiceActiveCoral,
    onError = Color.White,
)

@Composable
fun AntigrafityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    AntigrafityTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        content = content,
    )
}
