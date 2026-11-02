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
    primary = FanevaCyan,
    onPrimary = Color.Black,
    primaryContainer = FanevaNavyMedium,
    onPrimaryContainer = Color.White,
    secondary = FanevaEmerald,
    onSecondary = Color.Black,
    tertiary = FanevaAmber,
    onTertiary = Color.Black,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    error = FanevaRed
)

private val LightColorScheme = lightColorScheme(
    primary = FanevaNavyDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E8F0),
    onPrimaryContainer = FanevaNavyDark,
    secondary = FanevaCyan,
    onSecondary = Color.White,
    tertiary = FanevaEmerald,
    onTertiary = Color.White,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    error = FanevaRed
)

@Composable
fun FanevaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Use our tailored enterprise palette for consistent branding
    content: @Composable () -> Unit
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
        content = content
    )
}
