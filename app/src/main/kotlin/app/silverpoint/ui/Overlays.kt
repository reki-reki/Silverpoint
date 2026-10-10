package app.silverpoint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Rule-of-thirds lines over the viewfinder. */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val c = Color(0x66FFFFFF)
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), strokeWidth = 1.dp.toPx())
            drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), strokeWidth = 1.dp.toPx())
        }
    }
}

/** Luminance histogram from the preview: a soft filled curve on dark glass, with clipping warnings. */
@Composable
fun HistogramView(bins: IntArray, modifier: Modifier = Modifier) {
    Canvas(modifier.size(118.dp, 54.dp).clip(RoundedCornerShape(14.dp)).background(Sl.Scrim)) {
        val pad = 6.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val max = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val path = Path()
        val step = w / (bins.size - 1)
        path.moveTo(pad, pad + h)
        bins.forEachIndexed { i, v ->
            // Square root keeps small counts visible next to a large peak.
            path.lineTo(pad + i * step, pad + h - h * kotlin.math.sqrt(v / max))
        }
        path.lineTo(pad + w, pad + h)
        path.close()
        drawPath(path, Color(0xD9FFFFFF))
        // Clipping warnings at both ends.
        if (bins.first() > max * 0.02f) drawLine(Color(0xFF64B5F6), Offset(pad, pad), Offset(pad, pad + h), strokeWidth = 2.dp.toPx())
        if (bins.last() > max * 0.02f) drawLine(Sl.Accent, Offset(pad + w, pad), Offset(pad + w, pad + h), strokeWidth = 2.dp.toPx())
    }
}

/**
 * A horizon line that stays parallel to the real horizon, in portrait or landscape. [tiltDegrees] is
 * how far the phone is from level (-45..45) and turns the line green within one degree;
 * [deviceDegrees] is the phone's rotation from `OrientationEventListener` (0..359).
 */
@Composable
fun LevelOverlay(tiltDegrees: Float, deviceDegrees: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val level = abs(tiltDegrees) < 1f
        val colour = if (level) Sl.Ok else Color(0xCCFFFFFF)
        val half = size.width * 0.18f
        val centre = Offset(size.width / 2, size.height / 2)
        // The UI stays portrait, so the line turns by the whole phone rotation, not only the tilt.
        rotate(-deviceDegrees.toFloat(), centre) {
            drawLine(colour, Offset(centre.x - half, centre.y), Offset(centre.x - 12.dp.toPx(), centre.y), strokeWidth = 2.dp.toPx())
            drawLine(colour, Offset(centre.x + 12.dp.toPx(), centre.y), Offset(centre.x + half, centre.y), strokeWidth = 2.dp.toPx())
        }
    }
}
