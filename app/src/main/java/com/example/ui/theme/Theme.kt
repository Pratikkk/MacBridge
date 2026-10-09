package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CyanNeon,
    onPrimary = Slate950,
    primaryContainer = CyanDark,
    onPrimaryContainer = Slate50,
    secondary = EmeraldNeon,
    onSecondary = Slate950,
    secondaryContainer = EmeraldDark,
    onSecondaryContainer = Slate50,
    tertiary = VioletNeon,
    onTertiary = Slate950,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate200,
    error = RoseNeon,
    onError = Slate950,
    outline = Slate700,
    outlineVariant = Slate600
)

private val LightColorScheme = darkColorScheme(
    // Default to the sleek dark slate palette for tech bridge aesthetic
    primary = CyanNeon,
    onPrimary = Slate950,
    primaryContainer = Slate800,
    onPrimaryContainer = Slate50,
    secondary = EmeraldNeon,
    onSecondary = Slate950,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate200,
    outline = Slate700
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to rich dark mode for bridge telemetry
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
