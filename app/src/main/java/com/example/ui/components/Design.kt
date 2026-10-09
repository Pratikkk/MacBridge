package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*

@Composable
fun ScreenTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Slate400)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = Slate900, border = BorderStroke(1.dp, Slate800)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Blur affects only decoration, never text, touch targets, or the content layer.
 * Compose ignores blur before Android 12; the gradient remains a readable fallback. */
@Composable
fun FrostedBackdrop(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF303030), Slate900)))) {
        Box(Modifier.align(Alignment.TopEnd).offset(x = 25.dp, y = (-30).dp)
            .size(170.dp).blur(40.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded).background(Color.White.copy(alpha = 0.10f), CircleShape))
        Box(Modifier.align(Alignment.BottomStart).offset(x = (-35).dp, y = 40.dp)
            .size(140.dp).blur(32.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded).background(Color.White.copy(alpha = 0.06f), CircleShape))
    }
}
