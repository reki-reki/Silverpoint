package app.silverpoint.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A horizontal ruler dial, like the adjustment dial of Samsung's photo editor: the scale slides
 * under a fixed centre mark and snaps to stops. Each stop is a light tick under the finger, every
 * [isMajor] stop a firmer one with a label from [label], and the ends bump. Fling to travel far.
 *
 * [index] is the value shown; while the dial is not being touched it follows [index] (so an
 * automatic value can move it). [onIndex] is called once per stop crossed.
 */
@Composable
fun RulerDial(
    count: Int,
    index: Int,
    onIndex: (Int) -> Unit,
    isMajor: (Int) -> Boolean,
    label: (Int) -> String?,
    modifier: Modifier = Modifier,
    /** Colours under the scale (white balance, tint); null for a plain scale. */
    track: List<Color>? = null,
    /** False while the value is automatic: the scale is drawn dimmer until touched. */
    active: Boolean = true,
    spacing: Dp = 11.dp,
) {
    if (count < 2) return
    val haptics = rememberHaptics()
    val stepPx = with(LocalDensity.current) { spacing.toPx() }
    val maxPx = (count - 1) * stepPx
    val offset = remember { Animatable(index.coerceIn(0, count - 1) * stepPx) }
    var touching by remember { mutableStateOf(false) }
    var last by remember { mutableIntStateOf(index) }
    val report by rememberUpdatedState(onIndex)
    val scope = rememberCoroutineScope()

    // Follow the value from outside (auto exposure, another control) while nobody is dragging.
    LaunchedEffect(index, count, touching) {
        if (!touching) {
            last = index
            offset.animateTo(index.coerceIn(0, count - 1) * stepPx, spring(stiffness = 600f))
        }
    }

    fun crossed(px: Float) {
        val i = (px / stepPx).roundToInt().coerceIn(0, count - 1)
        if (i != last) {
            last = i
            when {
                i == 0 || i == count - 1 -> haptics.endStop()
                isMajor(i) -> haptics.majorDetent()
                else -> haptics.detent()
            }
            report(i)
        }
    }

    val state = rememberDraggableState { delta ->
        // The scale moves with the finger: dragging left brings higher values to the centre.
        val next = (offset.value - delta).coerceIn(0f, maxPx)
        scope.launch { offset.snapTo(next) }
        crossed(next)
    }
    // Room for every label on screen at once (the default cache of 8 thrashes on the ISO and shutter dials).
    val textMeasurer = rememberTextMeasurer(cacheSize = 32)
    val labelStyle = remember { TextStyle(color = Sl.TextDim, fontSize = 10.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center) }
    val markColour by animateColorAsState(if (active) Sl.Accent else Sl.Text, label = "mark")

    Canvas(
        modifier.fillMaxWidth().height(50.dp)
            .draggable(
                state,
                Orientation.Horizontal,
                onDragStarted = { touching = true },
                onDragStopped = { velocity ->
                    // Fling with the finger's speed, then settle on the nearest stop.
                    offset.updateBounds(0f, maxPx)
                    offset.animateDecay(-velocity, exponentialDecay(frictionMultiplier = 2.2f)) { crossed(value) }
                    val target = (offset.value / stepPx).roundToInt().coerceIn(0, count - 1)
                    crossed(target * stepPx)
                    offset.animateTo(target * stepPx, spring(stiffness = 700f))
                    touching = false
                },
            ),
    ) {
        val cx = size.width / 2f
        val base = size.height - 16.dp.toPx()
        val half = size.width / 2f
        track?.let {
            drawRoundRect(
                Brush.horizontalGradient(it, startX = cx - offset.value, endX = cx - offset.value + maxPx),
                topLeft = Offset(0f, base + 3.dp.toPx()), size = Size(size.width, 3.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()), alpha = 0.85f,
            )
        }
        val first = ((offset.value - half) / stepPx).toInt().coerceAtLeast(0)
        val lastTick = ((offset.value + half) / stepPx).toInt() + 1
        for (i in first..minOf(lastTick, count - 1)) {
            val x = cx + i * stepPx - offset.value
            // Ticks fade towards the edges, like a drum turning away.
            val fade = (1f - abs(x - cx) / half).coerceIn(0f, 1f)
            val major = isMajor(i)
            val h = if (major) 18.dp.toPx() else 10.dp.toPx()
            drawLine(
                Color.White.copy(alpha = (if (major) 0.9f else 0.45f) * (0.25f + 0.75f * fade) * (if (active) 1f else 0.7f)),
                Offset(x, base - h), Offset(x, base),
                strokeWidth = (if (major) 2f else 1.4f).dp.toPx(), cap = StrokeCap.Round,
            )
            if (major) label(i)?.let { text ->
                val m = textMeasurer.measure(text, labelStyle)
                drawText(m, topLeft = Offset(x - m.size.width / 2f, base + 4.dp.toPx()), alpha = 0.35f + 0.65f * fade)
            }
        }
        // The centre mark: what the dial is set to.
        val markTop = base - 28.dp.toPx()
        drawLine(markColour, Offset(cx, markTop + 6.dp.toPx()), Offset(cx, base + 2.dp.toPx()), strokeWidth = 2.6.dp.toPx(), cap = StrokeCap.Round)
        val tri = Path().apply {
            moveTo(cx - 5.dp.toPx(), markTop)
            lineTo(cx + 5.dp.toPx(), markTop)
            lineTo(cx, markTop + 6.dp.toPx())
            close()
        }
        drawPath(tri, markColour)
    }
}

/**
 * The line above a dial: the setting's name, its value (in the accent colour when set by hand)
 * and AUTO / RESET on the right. [extra] sits between, for white balance's Temp/Tint switch.
 */
@Composable
fun DialHeader(
    title: String,
    value: String,
    manual: Boolean,
    autoLabel: String,
    autoOn: Boolean,
    extra: (@Composable () -> Unit)? = null,
    onAuto: () -> Unit,
) {
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = LabelCaps, color = Sl.TextDim)
        Spacer(Modifier.width(10.dp))
        Text(
            value, color = if (manual) Sl.Accent else Sl.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        extra?.invoke()
        Spacer(Modifier.width(8.dp))
        Text(
            autoLabel.uppercase(),
            style = LabelCaps,
            color = if (autoOn) Sl.OnAccent else Sl.Text,
            modifier = Modifier.clip(CircleShape).background(if (autoOn) Sl.Accent else Sl.CardHigh)
                .clickable(enabled = !autoOn) { haptics.toggle(true); onAuto() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/** A small two-way switch (white balance: Temp | Tint). */
@Composable
fun MiniSegments(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val haptics = rememberHaptics()
    Row(Modifier.clip(CircleShape).background(Sl.CardHigh).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Text(
                o, style = LabelCaps, color = if (on) Color.Black else Sl.TextDim,
                modifier = Modifier.clip(CircleShape).background(if (on) Color(0xF2FFFFFF) else Color.Transparent)
                    .clickable { if (!on) { haptics.select(); onSelect(i) } }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** One of Pro mode's settings as a round button: its value inside, its name below. */
@Composable
fun ParamButton(
    label: String,
    value: String,
    manual: Boolean,
    selected: Boolean,
    enabled: Boolean = true,
    /** False for settings that have no automatic value (exposure compensation): no AUTO under it. */
    automatic: Boolean = true,
    onClick: () -> Unit,
) {
    val ring by animateColorAsState(
        when {
            selected -> Color.White
            manual -> Sl.Accent
            else -> Color(0x40FFFFFF)
        },
        label = "ring",
    )
    val fill by animateColorAsState(if (selected) Color(0x26FFFFFF) else Color.Transparent, label = "fill")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(fill).border(if (selected) 2.dp else 1.4.dp, ring, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    value,
                    color = when {
                        !enabled -> Sl.TextFaint
                        manual -> Sl.Accent
                        else -> Sl.Text
                    },
                    fontSize = if (value.length > 4) 11.sp else 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                // Automatic values move with the scene; say so instead of a cryptic letter.
                if (!manual && enabled && automatic) Text("AUTO", style = LabelCaps.copy(fontSize = 8.sp, letterSpacing = 0.6.sp), color = Sl.TextFaint)
            }
        }
        Text(label, style = LabelCaps.copy(fontSize = 10.sp), color = if (selected) Sl.Text else Sl.TextDim)
    }
}
