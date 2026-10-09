package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MonochromeScheme = darkColorScheme(
    primary = Slate50, onPrimary = Slate950,
    primaryContainer = Slate800, onPrimaryContainer = Slate50,
    secondary = Slate200, onSecondary = Slate950,
    secondaryContainer = Slate800, onSecondaryContainer = Slate50,
    tertiary = Slate200, onTertiary = Slate950,
    background = Slate950, onBackground = Slate50,
    surface = Slate900, onSurface = Slate50,
    surfaceVariant = Slate800, onSurfaceVariant = Slate400,
    error = RoseNeon, onError = Slate950,
    outline = Slate700, outlineVariant = Slate800
)

@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MonochromeScheme, typography = Typography, shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(22.dp),
        extraLarge = RoundedCornerShape(28.dp)), content = content)
}
