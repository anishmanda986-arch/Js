package com.jarvis.ai.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.jarvis.ai.voice.VoiceState

@Composable
fun OrbView(state: VoiceState, powerMode: String, modifier: Modifier = Modifier.size(190.dp)) {
    if (powerMode == "BATTERY SAVER" && state == VoiceState.IDLE) {
        Canvas(modifier) {
            drawCircle(MaterialTheme.colorScheme.primary, size.minDimension / 4f, style = Stroke(3.dp.toPx()))
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "orb")
    val scale by transition.animateFloat(
        0.90f, 1.08f,
        infiniteRepeatable(tween(if (state == VoiceState.THINKING) 600 else 1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val core = when (state) {
        VoiceState.IDLE -> Color(0xFF00E5FF)
        VoiceState.LISTENING -> Color(0xFFFF1744)
        VoiceState.THINKING -> Color(0xFFAA00FF)
        VoiceState.SPEAKING -> Color(0xFF00E676)
        VoiceState.ERROR -> Color(0xFFFF9100)
    }
    Canvas(modifier) {
        val r = size.minDimension / 3.4f * scale
        drawCircle(Brush.radialGradient(listOf(core.copy(alpha = .55f), Color.Transparent), r * 1.5f), r * 1.5f)
        drawCircle(core, r, style = Stroke(4.dp.toPx()))
    }
}
