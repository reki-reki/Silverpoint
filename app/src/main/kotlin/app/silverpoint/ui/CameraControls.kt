package app.silverpoint.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.silverpoint.AspectRatio
import app.silverpoint.FlashMode
import app.silverpoint.camera.ExposureMath
import kotlin.math.roundToInt

/** The settings Pro mode (and Photo mode's exposure button) can put on the dial. */
enum class DialParam { Ev, Iso, Shutter, Focus, WhiteBalance }

/** A top-bar control opened into its row of choices. */
enum class TopOption { Flash, Timer, Aspect, Aids }

// ---------------------------------------------------------------- top bar

/**
 * Settings, then the four things worth changing between shots: flash, self-timer, the photo's
 * shape and the viewfinder aids. Each shows its current state; tapping one turns the bar into its
 * choices (as Samsung's camera does), so nothing needs a menu.
 */
@Composable
fun TopBar(ui: CameraUi, act: CameraActions, open: TopOption?, onOpen: (TopOption?) -> Unit) {
    val haptics = rememberHaptics()
    AnimatedContent(open, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "topbar") { opened ->
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (opened == null) {
                BarButton(onClick = { haptics.tap(); act.onSettings() }) { GlyphIcon(Glyph.Settings, Sl.Text, size = 24.dp) }
                BarButton(enabled = ui.hasFlash, onClick = { haptics.tap(); onOpen(TopOption.Flash) }) { FlashIcon(if (ui.hasFlash) ui.flash else FlashMode.Off, dim = !ui.hasFlash) }
                BarButton(onClick = { haptics.tap(); onOpen(TopOption.Timer) }) { TimerIcon(ui.timerSeconds) }
                BarButton(onClick = { haptics.tap(); onOpen(TopOption.Aspect) }) { AspectIcon(ui.aspect, Sl.Text) }
                val aids = ui.grid || ui.level || ui.histogramOn || ui.zebras || ui.peaking
                BarButton(onClick = { haptics.tap(); onOpen(TopOption.Aids) }) { GlyphIcon(Glyph.Aids, if (aids) Sl.Accent else Sl.Text, size = 24.dp) }
            } else {
                BarButton(onClick = { haptics.tap(); onOpen(null) }) { GlyphIcon(Glyph.Close, Sl.TextDim, size = 20.dp) }
                when (opened) {
                    TopOption.Flash -> for (m in FlashMode.entries) {
                        Choice(m.label.removePrefix("Flash ").replaceFirstChar { it.uppercase() }, ui.flash == m, onClick = {
                            haptics.select(); act.onFlash(m); onOpen(null)
                        }) { c -> FlashIcon(m, colour = c) }
                    }
                    TopOption.Timer -> for (s in TIMER_CHOICES) {
                        Choice(if (s == 0) "Off" else "$s s", ui.timerSeconds == s, onClick = {
                            haptics.select(); act.onTimer(s); onOpen(null)
                        }) { c -> TimerIcon(s, colour = c) }
                    }
                    TopOption.Aspect -> for (a in AspectRatio.entries) {
                        Choice(a.label, ui.aspect == a, onClick = { haptics.select(); act.onAspect(a); onOpen(null) }) { c -> AspectShape(a, c) }
                    }
                    TopOption.Aids -> {
                        // Several can be on at once, so this row stays open until closed.
                        AidToggle("Grid", Glyph.Grid, ui.grid, act.onToggleGrid)
                        AidToggle("Level", Glyph.Level, ui.level, act.onToggleLevel)
                        AidToggle("Histogram", Glyph.Histogram, ui.histogramOn, act.onToggleHistogram)
                        AidToggle("Zebras", Glyph.Zebra, ui.zebras, act.onToggleZebras)
                        AidToggle("Peaking", Glyph.Peaking, ui.peaking, act.onTogglePeaking)
                    }
                }
            }
        }
    }
}

/** Self-timer choices: off, 2 s (no shake from the press), 5 s, 10 s (time to join the picture). */
val TIMER_CHOICES = listOf(0, 2, 5, 10)

@Composable
private fun BarButton(enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** One choice in an opened top-bar row: its picture over a short label, lit when chosen. */
@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val c by animateColorAsState(if (selected) Sl.Accent else Sl.Text, label = "choice")
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon(c) }
        Text(label, color = c, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun AidToggle(label: String, glyph: Glyph, on: Boolean, onToggle: () -> Unit) {
    val haptics = rememberHaptics()
    Choice(label, on, onClick = { haptics.toggle(!on); onToggle() }) { c -> GlyphIcon(glyph, c, size = 22.dp) }
}

@Composable
private fun FlashIcon(mode: FlashMode, colour: Color? = null, dim: Boolean = false) {
    val tint = colour ?: when {
        dim -> Sl.TextFaint
        mode == FlashMode.On -> Sl.Accent
        else -> Sl.Text
    }
    Box(contentAlignment = Alignment.Center) {
        GlyphIcon(if (mode == FlashMode.Off) Glyph.FlashOff else Glyph.Flash, tint, size = 24.dp)
        if (mode == FlashMode.Auto) {
            Text("A", color = tint, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 2.dp))
        }
    }
}

@Composable
private fun TimerIcon(seconds: Int, colour: Color? = null) {
    val tint = colour ?: if (seconds > 0) Sl.Accent else Sl.Text
    Box(contentAlignment = Alignment.Center) {
        GlyphIcon(if (seconds > 0) Glyph.TimerFace else Glyph.Timer, tint, size = 24.dp)
        // Like Samsung's timer icon: the seconds sit inside the clock face.
        if (seconds > 0) {
            Text("$seconds", color = tint, fontSize = if (seconds > 9) 9.sp else 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(y = 1.6.dp))
        }
    }
}

/** The aspect ratio as Samsung's camera shows it: the numbers in a frame of that shape. */
@Composable
private fun AspectIcon(a: AspectRatio, tint: Color) {
    Box(
        Modifier.size(width = 30.dp, height = 22.dp).border(1.6.dp, tint, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) { Text(a.label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

/** A small portrait frame with the ratio's real proportions. */
@Composable
private fun AspectShape(a: AspectRatio, tint: Color) {
    val h = 24f
    val w = h / a.ratio
    Box(Modifier.size(w.dp, h.dp).border(1.6.dp, tint, RoundedCornerShape(3.dp)))
}

// ---------------------------------------------------------------- just above the controls

/**
 * The row along the bottom of the viewfinder: exposure on the left (Photo mode; Pro mode has its
 * own EV dial), the lenses in the middle where the thumb reaches them.
 */
@Composable
fun ViewfinderRow(ui: CameraUi, evOpen: Boolean, onEv: () -> Unit, act: CameraActions) {
    Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 14.dp)) {
        if (!ui.pro && ui.permission) EvButton(ui.evThirds, evOpen, Modifier.align(Alignment.CenterStart), onEv)
        Box(Modifier.align(Alignment.Center)) { LensSelector(ui.lenses, ui.lens, act.onLens) }
    }
}

/** The lens buttons: 0.6, 1, 3, 5 on the S25 Ultra; the chosen one is a bright disc. */
@Composable
fun LensSelector(lenses: List<LensUi>, selected: String?, onSelect: (String) -> Unit) {
    if (lenses.size < 2) return
    val haptics = rememberHaptics()
    Row(
        Modifier.clip(CircleShape).background(Sl.Scrim).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (l in lenses) {
            val on = l.id == selected
            val size by animateDpAsState(if (on) 42.dp else 36.dp, label = "lens")
            Box(
                Modifier.size(size).clip(CircleShape).background(if (on) Color(0xEBFFFFFF) else Color.Transparent)
                    .clickable {
                        if (!on) haptics.select()
                        onSelect(l.id)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (on) "${l.text}×" else l.text,
                    color = if (on) Color.Black else Sl.Text,
                    fontSize = if (on) 13.sp else 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

/** Photo mode's exposure button: a ± that shows the compensation once there is some. */
@Composable
fun EvButton(thirds: Int, open: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val set = thirds != 0
    Box(
        modifier.size(44.dp).clip(CircleShape).background(if (open) Color(0x33FFFFFF) else Sl.Scrim)
            .border(1.2.dp, if (open || set) Sl.Accent else Color.Transparent, CircleShape)
            .clickable { haptics.tap(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (set) evText(thirds) else "±",
            color = if (set) Sl.Accent else Sl.Text,
            fontSize = if (set) 12.sp else 19.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** "+0.3", "−1.0" for thirds of a stop. */
fun evText(thirds: Int): String {
    val v = thirds / 3f
    return (if (thirds > 0) "+" else if (thirds < 0) "−" else "±") + String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(v))
}

// ---------------------------------------------------------------- looks bar (Photo mode)

/** A corner radius that makes any look picture a circle. */
private val ROUND = 999.dp

/**
 * Photo mode's quick look switcher: only the starred looks, as live thumbnails of the scene. The
 * chosen look's name shows briefly over the picture when it changes (see CameraLayout) rather than
 * in a permanent line here, which kept the row off the picture on a 384 dp-wide phone. The full
 * selector (every look, by kind) is the button right of the shutter; this row is for flicking
 * between the few you use.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LookBar(ui: CameraUi, onPick: (String) -> Unit, onToggleFavourite: (String) -> Unit, onOpenAll: () -> Unit) {
    val haptics = rememberHaptics()
    val selected = ui.recipes.firstOrNull { it.name == ui.recipe }
    val starred = ui.recipes.filter { it.name in ui.favourites }
    // The chosen look leads when it is not starred, so it is always visible.
    val shown = if (selected != null && selected.name !in ui.favourites) listOf(selected) + starred else starred
    val list = rememberLazyListState()
    LaunchedEffect(ui.recipe, shown.size) {
        val i = shown.indexOfFirst { it.name == ui.recipe }
        if (i >= 0) list.animateScrollToItem((i - 2).coerceAtLeast(0))
    }
    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
        if (shown.isEmpty()) {
            Text("Star looks in the selector to keep them here", color = Sl.TextDim, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onOpenAll))
        } else {
            LazyRow(
                state = list,
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                contentPadding = PaddingValues(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(shown, key = { it.name }) { r ->
                    val on = r.name == ui.recipe
                    val size by animateDpAsState(if (on) 52.dp else 44.dp, label = "look")
                    Box(
                        Modifier.size(size).combinedClickable(
                            onClick = { if (!on) { haptics.select(); onPick(r.name) } },
                            onLongClick = { haptics.longPress(); onToggleFavourite(r.name) },
                        ),
                    ) {
                        LookCircle(r, ui.lookThumbs[r.name], size, ring = if (on) Color.White else Color(0x33FFFFFF), ringWidth = if (on) 2.dp else 1.dp)
                    }
                }
            }
        }
    }
}

/** A look as a circle: the camera through it when live previews are on, else the sample picture through it (see [LookPicture]). */
@Composable
fun LookCircle(r: RecipeUi, thumb: ImageBitmap?, size: Dp, ring: Color, ringWidth: Dp = 1.dp) {
    Box(Modifier.size(size).clip(CircleShape).border(ringWidth, ring, CircleShape)) {
        // A fixed radius larger than any circle (the renderer caps it at half the width), so the size
        // animation of the quick row resizes the live surface instead of replacing it.
        LookPicture(r.name, thumb, r.swatch, radius = ROUND, modifier = Modifier.fillMaxSize().clip(CircleShape))
    }
}

// ---------------------------------------------------------------- Pro mode

/**
 * Pro mode: the five exposure settings as round buttons showing their values, and under them the
 * dial for the one picked (tap it again to put the dial away). Samsung's photo editor works the
 * same way, so the pattern is familiar.
 */
@Composable
fun ProDeck(ui: CameraUi, open: DialParam?, onOpen: (DialParam?) -> Unit) {
    val pro = ui.proValues ?: return
    val haptics = rememberHaptics()
    // The open dial is drawn by CameraLayout in the lens row's place, so the two swap in one motion.
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            fun pick(p: DialParam) { haptics.tap(); onOpen(if (open == p) null else p) }
            ParamButton("EV", if (pro.fullManual) "off" else evText(ui.evThirds), manual = ui.evThirds != 0 && !pro.fullManual, selected = open == DialParam.Ev, enabled = !pro.fullManual, automatic = false) { pick(DialParam.Ev) }
            ParamButton("ISO", (pro.iso ?: pro.liveIso)?.toString() ?: "–", pro.iso != null, open == DialParam.Iso) { pick(DialParam.Iso) }
            ParamButton("SPEED", (pro.shutterNs ?: pro.liveShutterNs)?.let(ExposureMath::shutterLabel) ?: "–", pro.shutterNs != null, open == DialParam.Shutter) { pick(DialParam.Shutter) }
            if (pro.hasAutofocus) {
                ParamButton("FOCUS", (pro.focus ?: pro.liveFocus)?.let(ExposureMath::focusLabel) ?: "–", pro.focus != null, open == DialParam.Focus) { pick(DialParam.Focus) }
            }
            ParamButton("WB", pro.kelvin?.let { "${it}K" } ?: "AWB", pro.kelvin != null, open == DialParam.WhiteBalance) { pick(DialParam.WhiteBalance) }
        }
    }
}

/** The dial for one setting, with its name, value and Auto/Reset above it. */
@Composable
fun DialPanel(param: DialParam, ui: CameraUi, act: CameraActions) {
    val pro = ui.proValues
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        when (param) {
            DialParam.Ev -> {
                val off = ui.pro && pro?.fullManual == true
                DialHeader("Exposure", if (off) "off: ISO and shutter are both set" else "${evText(ui.evThirds)} EV", manual = ui.evThirds != 0, autoLabel = "Reset", autoOn = ui.evThirds == 0) { act.onEv(0) }
                if (!off) {
                    RulerDial(
                        count = 13, index = ui.evThirds + 6, onIndex = { act.onEv(it - 6) },
                        isMajor = { it % 3 == 0 }, label = { i -> (i - 6).let { t -> if (t == 0) "0" else evText(t).removeSuffix(".0") } },
                        spacing = 22.dp,
                    )
                }
            }
            DialParam.Iso -> if (pro != null) {
                val stops = pro.isoStops
                val current = pro.iso ?: pro.liveIso ?: 100
                DialHeader("ISO", "$current", manual = pro.iso != null, autoLabel = "Auto", autoOn = pro.iso == null) { act.onIso(null) }
                RulerDial(
                    count = stops.size, index = stops.nearest(current), onIndex = { act.onIso(stops[it]) },
                    isMajor = { stops[it] in WHOLE_ISO }, label = { "${stops[it]}" }, active = pro.iso != null, spacing = 16.dp,
                )
            }
            DialParam.Shutter -> if (pro != null) {
                val stops = pro.shutterStops
                val current = pro.shutterNs ?: pro.liveShutterNs ?: 10_000_000L
                // Formatted once, not twice per visible mark on every frame of a drag.
                val labels = remember(stops) { stops.map(ExposureMath::shutterLabel) }
                DialHeader("Shutter", ExposureMath.shutterLabel(current), manual = pro.shutterNs != null, autoLabel = "Auto", autoOn = pro.shutterNs == null) { act.onShutterSpeed(null) }
                RulerDial(
                    count = stops.size, index = stops.nearestLong(current), onIndex = { act.onShutterSpeed(stops[it]) },
                    isMajor = { labels[it] in WHOLE_SHUTTER }, label = { labels[it] },
                    active = pro.shutterNs != null, spacing = 15.dp,
                )
            }
            DialParam.Focus -> if (pro != null && pro.hasAutofocus) {
                val steps = 100
                val current = pro.focus ?: pro.liveFocus ?: 0f
                DialHeader("Focus", ExposureMath.focusLabel(current), manual = pro.focus != null, autoLabel = "AF", autoOn = pro.focus == null) { act.onFocus(null) }
                RulerDial(
                    count = steps + 1, index = ((current / pro.maxFocus) * steps).roundToInt().coerceIn(0, steps),
                    onIndex = { act.onFocus(pro.maxFocus * it / steps) },
                    isMajor = { it % 10 == 0 }, label = { i -> if (i % 20 == 0) ExposureMath.focusLabel(pro.maxFocus * i / steps) else null },
                    active = pro.focus != null, spacing = 9.dp,
                )
            }
            DialParam.WhiteBalance -> if (pro != null) {
                var tintMode by rememberSaveable { mutableIntStateOf(0) }
                DialHeader(
                    "WB",
                    pro.kelvin?.let { "$it K" + if (pro.tint != 0) "  ${if (pro.tint > 0) "+" else ""}${pro.tint}" else "" } ?: "Auto",
                    manual = pro.kelvin != null, autoLabel = "Auto", autoOn = pro.kelvin == null,
                    onAuto = { act.onWhiteBalance(null, pro.tint) },
                    extra = { if (pro.kelvin != null) MiniSegments(listOf("TEMP", "TINT"), tintMode) { tintMode = it } },
                )
                if (tintMode == 1 && pro.kelvin != null) {
                    RulerDial(
                        count = 101, index = pro.tint + 50, onIndex = { act.onWhiteBalance(pro.kelvin, it - 50) },
                        isMajor = { it % 10 == 0 }, label = { i -> (i - 50).let { if (it > 0) "+$it" else "$it" } }, track = tintTrack, spacing = 9.dp,
                    )
                } else {
                    RulerDial(
                        count = KELVINS.size, index = KELVINS.nearest(pro.kelvin ?: 5500), onIndex = { act.onWhiteBalance(KELVINS[it], pro.tint) },
                        isMajor = { KELVINS[it] % 500 == 0 }, label = { i -> if (KELVINS[i] % 1000 == 0) "${KELVINS[i] / 1000}K" else null },
                        track = kelvinTrack, active = pro.kelvin != null, spacing = 9.dp,
                    )
                }
            }
        }
    }
}

/** Whole stops of ISO and shutter: the labelled, firmer detents. */
private val WHOLE_ISO = setOf(25, 50, 100, 200, 400, 800, 1600, 3200, 6400)
private val WHOLE_SHUTTER = setOf("1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1/8", "1/4", "0.5 s", "1.0 s")

/** White balance stops: 100 K apart from 2500 to 10000 K. */
private val KELVINS = (2500..10000 step 100).toList()

/** Blue to amber: what the Kelvin dial does to the photo (higher Kelvin, warmer photo). */
private val kelvinTrack = listOf(Color(0xFF6FA8FF), Color(0xFFFFFFFF), Color(0xFFFFB45C))

/** Green to magenta for tint. */
private val tintTrack = listOf(Color(0xFF7BD88F), Color(0xFFFFFFFF), Color(0xFFE47FD8))

private fun List<Int>.nearest(v: Int): Int = indices.minByOrNull { kotlin.math.abs(this[it] - v) } ?: 0

private fun List<Long>.nearestLong(v: Long): Int = indices.minByOrNull { kotlin.math.abs(this[it] - v) } ?: 0

// ---------------------------------------------------------------- mode and shutter

/** PHOTO and PRO, One UI's mode pill. Always on screen, so Pro mode can always be left. */
@Composable
fun ModeTabs(pro: Boolean, onPro: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        for ((label, isPro) in listOf("PHOTO" to false, "PRO" to true)) {
            val selected = pro == isPro
            val bg by animateColorAsState(if (selected) Color(0xFF2B2B30) else Color.Transparent, label = "tab")
            Text(
                label,
                color = if (selected) Sl.Text else Sl.TextDim,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(horizontal = 4.dp).clip(CircleShape).background(bg)
                    .clickable {
                        if (!selected) haptics.select()
                        onPro(isPro)
                    }
                    .padding(horizontal = 18.dp, vertical = 7.dp),
            )
        }
    }
}

/** Gallery, shutter, looks: what you open, what you press, what you choose. */
@Composable
fun ShutterRow(ui: CameraUi, act: CameraActions, onLooks: () -> Unit) {
    val haptics = rememberHaptics()
    val selected = ui.recipes.firstOrNull { it.name == ui.recipe }
    Row(
        Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 34.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GalleryButton(ui.thumbnail, ui.developing) { haptics.tap(); act.onGallery() }
        ShutterButton(
            busy = ui.busy,
            counting = ui.countdown > 0,
            enabled = ui.permission && (!ui.busy || ui.countdown > 0),
            onClick = { haptics.shutter(); act.onShutter() },
        )
        // The look selector: the current look on the scene, with the looks mark.
        Box(Modifier.size(64.dp).clip(CircleShape).clickable { haptics.tap(); onLooks() }, contentAlignment = Alignment.Center) {
            if (selected != null) LookCircle(selected, ui.lookThumbs[selected.name], 52.dp, ring = Color(0xCCFFFFFF), ringWidth = 1.5.dp)
            Box(Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(Sl.Black).padding(2.dp).clip(CircleShape).background(Sl.CardHigh), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Looks, Sl.Text, size = 15.dp)
            }
        }
    }
}

@Composable
private fun GalleryButton(thumbnail: ImageBitmap?, developing: Int, onClick: () -> Unit) {
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(Sl.CardHigh)
                .border(1.5.dp, Color(0xCCFFFFFF), CircleShape)
                .clickable(enabled = thumbnail != null, onClick = onClick),
        ) {
            thumbnail?.let { Image(it, contentDescription = "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        // While photos develop, a ring runs round the thumbnail, as in Samsung's camera.
        if (developing > 0) {
            SpinnerRing(64.dp, Sl.Accent)
            Text(
                "$developing", color = Sl.OnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(Sl.Accent).padding(top = 2.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ShutterButton(busy: Boolean, counting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, label = "press")
    val fill by animateColorAsState(
        when {
            counting -> Sl.Accent
            busy || !enabled -> Color(0xFF8E8E93)
            else -> Color.White
        },
        label = "fill",
    )
    Box(
        Modifier.size(84.dp).clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White, radius = size.minDimension / 2f - 2.dp.toPx(), style = Stroke(3.5.dp.toPx()))
        }
        Box(Modifier.size(66.dp).scale(scale).clip(CircleShape).background(fill))
        if (busy) SpinnerRing(84.dp, Sl.Accent)
    }
}

/** An arc that runs round a circle: something is being worked on. */
@Composable
fun SpinnerRing(size: Dp, color: Color) {
    // Previews and screenshot tests cannot wait for an endless animation: draw it still there.
    // The angle is read in the layer, not in composition, so the ring turns without recomposing.
    val angle: State<Float>? = if (LocalInspectionMode.current) null else {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    }
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = angle?.value ?: 0f }) {
        val w = 3.dp.toPx()
        drawArc(color, 0f, 100f, useCenter = false, topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

// ---------------------------------------------------------------- over the viewfinder

/** A small rounded label over the viewfinder. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, background: Color = Sl.Scrim, color: Color = Sl.Text, dot: Color? = null) {
    if (text.isEmpty()) return
    Row(
        modifier.clip(CircleShape).background(background).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dot?.let { Box(Modifier.size(7.dp).clip(CircleShape).background(it)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** An error or notice; tap to dismiss. */
@Composable
fun MessagePill(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 18.dp).clip(RoundedCornerShape(18.dp)).background(Sl.ScrimStrong)
            .clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Sl.Danger))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Sl.Text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Big self-timer digits in the middle of the viewfinder. */
@Composable
fun CountdownText(seconds: Int, modifier: Modifier = Modifier) {
    Text(
        "$seconds",
        modifier = modifier,
        style = TextStyle(
            color = Color.White, fontSize = 104.sp, fontWeight = FontWeight.Light,
            shadow = Shadow(Color(0x99000000), Offset(0f, 4f), 18f),
        ),
    )
}

/** Where the user tapped: a rounded square that turns green once focus has locked. */
@Composable
fun FocusMark(u: Float, v: Float, locked: Boolean) {
    val colour by animateColorAsState(if (locked) Sl.Ok else Color.White, label = "focus")
    Canvas(Modifier.fillMaxSize()) {
        val side = 68.dp.toPx()
        val c = Offset(u * size.width, v * size.height)
        drawRoundRect(colour, Offset(c.x - side / 2, c.y - side / 2), Size(side, side), CornerRadius(14.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        drawCircle(colour, 2.dp.toPx(), c)
    }
}

/**
 * Covers everything outside [visible] (and rounds its corners) in [colour]. The camera draws
 * through a SurfaceView, which Compose clipping cannot crop or round, so the rest is painted over.
 */
@Composable
fun FrameMask(visible: Rect, radius: Dp, colour: Color = Sl.Black) {
    Canvas(Modifier.fillMaxSize()) {
        val outer = Path().apply { addRect(Rect(Offset.Zero, size)) }
        val inner = Path().apply { addRoundRect(RoundRect(visible, CornerRadius(radius.toPx()))) }
        drawPath(Path.combine(PathOperation.Difference, outer, inner), colour)
    }
}

/** Shown in place of the viewfinder until the camera may be used. */
@Composable
fun PermissionCard(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Sl.AccentDeep), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.Camera, Sl.Accent, size = 34.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text("Silverpoint needs the camera", color = Sl.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Photos are taken in RAW and developed on the phone. Nothing leaves it.", color = Sl.TextDim, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        AccentPill("Allow camera", onClick = onAllow)
    }
}
