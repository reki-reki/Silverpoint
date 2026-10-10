package app.silverpoint.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Silverpoint's mark: silver-halide grains on a golden-angle spiral converging on one point of
 * light. The same geometry is in scripts/make_logo.py (the launcher icon) and motion/src/Mark.ts
 * (the start-up animation, whose last frame this must match): grain i (from 1) at radius
 * 3·sqrt(i) and angle i·137.508°, until the radius passes [R_MAX], in mark units.
 */
object SilverpointMark {
    const val R_MAX = 38f
    private const val GOLDEN = (137.508 * Math.PI / 180).toFloat()
    private const val GLOW_R = 11f
    private const val POINT_R = 3.2f
    private val GRAIN = Color(0xFFD9DDE3)

    /** (x, y, size, alpha) per grain, in mark units around 0. */
    private val grains: List<FloatArray> = buildList {
        var i = 1
        while (true) {
            val r = 3f * sqrt(i.toFloat())
            if (r > R_MAX) break
            add(floatArrayOf(r * cos(i * GOLDEN), r * sin(i * GOLDEN), 0.35f + r / R_MAX * 1.25f, 0.35f + 0.65f * (1 - r / 40f)))
            i++
        }
    }

    /** Draws the mark centred on [center], its outermost grains [radius] pixels out; [alpha] fades it. */
    fun DrawScope.drawMark(center: Offset, radius: Float, alpha: Float = 1f) {
        val unit = radius / R_MAX
        for (g in grains) {
            drawCircle(GRAIN, radius = g[2] * unit, center = Offset(center.x + g[0] * unit, center.y + g[1] * unit), alpha = g[3] * alpha)
        }
        drawCircle(
            Brush.radialGradient(
                0f to Color.White,
                0.25f to Color(0xD9E8EEF6),
                1f to Color(0x009FB3C8),
                center = center,
                radius = GLOW_R * unit,
            ),
            radius = GLOW_R * unit,
            center = center,
            alpha = alpha,
        )
        drawCircle(Color.White, radius = POINT_R * unit, center = center, alpha = alpha)
    }
}
