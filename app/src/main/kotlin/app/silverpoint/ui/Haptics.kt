package app.silverpoint.ui

import android.content.Context
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/** Whether the user wants haptics (Settings → Haptics). */
val LocalHapticsEnabled = compositionLocalOf { true }

/**
 * Silverpoint's haptic vocabulary. Each kind of event has one feel, so the hand learns them:
 * light ticks for dial stops, a firmer tick on whole stops and round numbers, a bump at the end
 * of a dial, toggles that feel on or off, a crisp tap on the shutter and a confirmation when the
 * photo is taken.
 *
 * Plays through the vibrator directly, not `View.performHapticFeedback`: the view path is
 * silently dropped when the phone's touch vibration is off and for feedback constants a phone does
 * not map, which is why 0.9.6's haptics did nothing on the S25 Ultra. Effects are tagged as
 * hardware feedback (they emulate a physical dial and shutter button), which follows the phone's
 * vibration strength but not the touch-vibration switch; Settings → Haptics turns them off.
 * Composition primitives give crisp ticks where the motor supports them, the standard predefined
 * effects otherwise.
 */
class Haptics(private val vibrator: Vibrator?, private val enabled: State<Boolean>) {
    private val attributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
    private val supported: Set<Int> = vibrator?.takeIf { it.hasVibrator() }?.let { v ->
        val ok = v.arePrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_THUD,
        )
        listOf(
            VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_THUD,
        ).filterIndexed { i, _ -> ok[i] }.toSet()
    } ?: emptySet()
    private var lastDetentAt = 0L

    /** One stop of a dial (a third of a stop, 100 K, a focus step). */
    fun detent() {
        // A fast fling crosses stops quicker than a motor can tick; keep them distinct.
        val now = SystemClock.uptimeMillis()
        if (now - lastDetentAt < 18) return
        lastDetentAt = now
        play(primitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.9f) ?: primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f), VibrationEffect.EFFECT_TICK)
    }

    /** A whole stop or a labelled mark on a dial. */
    fun majorDetent() = play(primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1f), VibrationEffect.EFFECT_TICK)

    /** A dial hit its first or last value. */
    fun endStop() = play(primitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.7f) ?: primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f), VibrationEffect.EFFECT_HEAVY_CLICK)

    /** One choice among several: a lens, a mode, an aspect ratio, a look. */
    fun select() = play(primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f), VibrationEffect.EFFECT_TICK)

    /** A switch-like control turned on or off. */
    fun toggle(on: Boolean) =
        if (on) play(primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f), VibrationEffect.EFFECT_CLICK)
        else play(primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f), VibrationEffect.EFFECT_TICK)

    /** Opening or closing a panel. */
    fun tap() = play(primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f), VibrationEffect.EFFECT_TICK)

    /** The shutter is pressed. */
    fun shutter() = play(primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f), VibrationEffect.EFFECT_CLICK)

    /** The photo was captured, or focus locked: a click and a lighter echo. */
    fun confirm() = play(
        composition(VibrationEffect.Composition.PRIMITIVE_CLICK to 0.6f, VibrationEffect.Composition.PRIMITIVE_TICK to 1f, gapMs = 60),
        VibrationEffect.EFFECT_CLICK,
    )

    /** Something failed: two firm knocks. */
    fun reject() = play(
        composition(VibrationEffect.Composition.PRIMITIVE_THUD to 0.8f, VibrationEffect.Composition.PRIMITIVE_THUD to 0.8f, gapMs = 90)
            ?: composition(VibrationEffect.Composition.PRIMITIVE_CLICK to 1f, VibrationEffect.Composition.PRIMITIVE_CLICK to 1f, gapMs = 90),
        VibrationEffect.EFFECT_DOUBLE_CLICK,
    )

    /** A long press did something (star a look). */
    fun longPress() = play(primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f), VibrationEffect.EFFECT_HEAVY_CLICK)

    private fun primitive(id: Int, scale: Float): VibrationEffect? =
        if (id in supported) VibrationEffect.startComposition().addPrimitive(id, scale).compose() else null

    private fun composition(a: Pair<Int, Float>, b: Pair<Int, Float>, gapMs: Int): VibrationEffect? =
        if (a.first in supported && b.first in supported) {
            VibrationEffect.startComposition().addPrimitive(a.first, a.second).addPrimitive(b.first, b.second, gapMs).compose()
        } else null

    private fun play(effect: VibrationEffect?, fallback: Int) {
        val v = vibrator ?: return
        if (!enabled.value) return
        runCatching { v.vibrate(effect ?: VibrationEffect.createPredefined(fallback), attributes) }
    }

    companion object {
        fun vibrator(context: Context): Vibrator? =
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val context = LocalContext.current.applicationContext
    val inspection = LocalInspectionMode.current
    val enabled = rememberUpdatedState(LocalHapticsEnabled.current)
    return remember(context, inspection) { Haptics(if (inspection) null else Haptics.vibrator(context), enabled) }
}
