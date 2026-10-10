package app.silverpoint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The few icons Silverpoint needs, drawn as lines so no icon library is required. */
enum class Glyph { Settings, Timer, TimerFace, Grid, Level, Histogram, Zebra, Peaking, Back, Forward, Camera, Star, StarOutline, Flash, FlashOff, Looks, Aids, Close }

/** Draws [glyph] in [tint], designed on a 24-unit grid like Material and One UI icons. */
@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val line = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun seg(x0: Float, y0: Float, x1: Float, y1: Float, w: Float = 1.8f) =
            drawLine(tint, p(x0, y0), p(x1, y1), strokeWidth = w * u, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.Settings -> {
                drawCircle(tint, 3.1f * u, p(12f, 12f), style = line)
                drawCircle(tint, 6.6f * u, p(12f, 12f), style = line)
                for (k in 0 until 8) {
                    val a = k * PI / 4
                    seg(12f + 7.4f * cos(a).toFloat(), 12f + 7.4f * sin(a).toFloat(), 12f + 9.6f * cos(a).toFloat(), 12f + 9.6f * sin(a).toFloat(), 2.6f)
                }
            }
            Glyph.Timer -> {
                drawCircle(tint, 7.6f * u, p(12f, 13.4f), style = line)
                seg(12f, 13.4f, 12f, 9.2f)
                seg(12f, 13.4f, 14.6f, 15.2f)
                seg(9.6f, 2.8f, 14.4f, 2.8f)
                seg(18.6f, 6.2f, 19.8f, 5.0f)
            }
            Glyph.TimerFace -> {
                // The clock without hands, for a number inside.
                drawCircle(tint, 8.6f * u, p(12f, 13.4f), style = line)
                seg(9.6f, 2.4f, 14.4f, 2.4f)
            }
            Glyph.Grid -> {
                drawRoundRect(tint, p(3.5f, 3.5f), Size(17f * u, 17f * u), CornerRadius(3.5f * u), style = line)
                for (t in floatArrayOf(9.2f, 14.8f)) {
                    seg(t, 3.5f, t, 20.5f, 1.4f)
                    seg(3.5f, t, 20.5f, t, 1.4f)
                }
            }
            Glyph.Level -> {
                seg(2.5f, 12f, 8.2f, 12f)
                seg(15.8f, 12f, 21.5f, 12f)
                drawCircle(tint, 3.2f * u, p(12f, 12f), style = line)
            }
            Glyph.Histogram -> {
                val bars = floatArrayOf(6f, 11f, 15.5f, 9f, 4.5f)
                bars.forEachIndexed { i, h ->
                    val x = 3.6f + i * 3.6f
                    drawRoundRect(tint, p(x, 19.5f - h), Size(2.4f * u, h * u), CornerRadius(1.2f * u))
                }
                seg(2.5f, 21f, 21.5f, 21f, 1.4f)
            }
            Glyph.Zebra -> {
                drawRoundRect(tint, p(3.5f, 3.5f), Size(17f * u, 17f * u), CornerRadius(3.5f * u), style = line)
                clipRect(3.5f * u, 3.5f * u, 20.5f * u, 20.5f * u) {
                    for (k in 0 until 5) {
                        val o = -6f + k * 6f
                        drawLine(tint, p(o, 22f), p(o + 18f, 4f), strokeWidth = 2.2f * u)
                    }
                }
            }
            Glyph.Peaking -> {
                val l = 5.5f
                for ((cx, cy) in listOf(3.5f to 3.5f, 20.5f to 3.5f, 3.5f to 20.5f, 20.5f to 20.5f)) {
                    val dx = if (cx < 12f) l else -l
                    val dy = if (cy < 12f) l else -l
                    seg(cx, cy, cx + dx, cy)
                    seg(cx, cy, cx, cy + dy)
                }
                val d = Path().apply {
                    moveTo(12f * u, 8f * u); lineTo(16f * u, 12f * u); lineTo(12f * u, 16f * u); lineTo(8f * u, 12f * u); close()
                }
                drawPath(d, tint)
            }
            Glyph.Back -> {
                seg(15f, 4.5f, 7.5f, 12f, 2.2f)
                seg(7.5f, 12f, 15f, 19.5f, 2.2f)
            }
            Glyph.Forward -> {
                seg(9f, 4.5f, 16.5f, 12f, 2.2f)
                seg(16.5f, 12f, 9f, 19.5f, 2.2f)
            }
            Glyph.Camera -> {
                drawRoundRect(tint, p(2.5f, 6.5f), Size(19f * u, 13.5f * u), CornerRadius(3.5f * u), style = line)
                seg(8.5f, 6.5f, 10f, 3.8f)
                seg(10f, 3.8f, 14f, 3.8f)
                seg(14f, 3.8f, 15.5f, 6.5f)
                drawCircle(tint, 3.8f * u, p(12f, 13.2f), style = line)
            }
            Glyph.Flash, Glyph.FlashOff -> {
                val bolt = Path().apply {
                    moveTo(13.6f * u, 2.6f * u); lineTo(5.6f * u, 13.4f * u); lineTo(11.2f * u, 13.4f * u)
                    lineTo(10.2f * u, 21.4f * u); lineTo(18.4f * u, 10.4f * u); lineTo(12.8f * u, 10.4f * u); close()
                }
                drawPath(bolt, tint, style = line)
                if (glyph == Glyph.FlashOff) seg(4f, 3.5f, 20f, 20.5f, 2f)
            }
            Glyph.Looks -> {
                // Three overlapping discs: colour channels, or a stack of film looks.
                drawCircle(tint, 5.2f * u, p(12f, 8.6f), style = line)
                drawCircle(tint, 5.2f * u, p(8.6f, 14.6f), style = line)
                drawCircle(tint, 5.2f * u, p(15.4f, 14.6f), style = line)
            }
            Glyph.Aids -> {
                // A viewfinder frame with a crosshair: guides drawn over the picture.
                for ((cx, cy) in listOf(3.5f to 5.5f, 20.5f to 5.5f, 3.5f to 18.5f, 20.5f to 18.5f)) {
                    val dx = if (cx < 12f) 4.5f else -4.5f
                    val dy = if (cy < 12f) 4f else -4f
                    seg(cx, cy, cx + dx, cy)
                    seg(cx, cy, cx, cy + dy)
                }
                seg(12f, 9f, 12f, 15f, 1.6f)
                seg(9f, 12f, 15f, 12f, 1.6f)
            }
            Glyph.Close -> {
                seg(6f, 6f, 18f, 18f, 2f)
                seg(18f, 6f, 6f, 18f, 2f)
            }
            Glyph.Star -> drawPath(star(u), tint)
            Glyph.StarOutline -> drawPath(star(u), tint, style = line)
        }
    }
}

private fun DrawScope.star(u: Float): Path = Path().apply {
    for (k in 0 until 10) {
        val r = if (k % 2 == 0) 9.5f else 4.2f
        val a = -PI / 2 + k * PI / 5
        val x = (12f + r * cos(a).toFloat()) * u
        val y = (12.8f + r * sin(a).toFloat()) * u
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
