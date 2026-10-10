package app.silverpoint.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import app.silverpoint.R
import kotlinx.coroutines.delay

private class Page(val kicker: String, val title: String, val body: String)

private val PAGES = listOf(
    Page(
        "Welcome",
        "Silverpoint",
        "A RAW camera that skips the phone's own processing and develops every shot with a film look, the way a darkroom would. Photos go straight to your Gallery, in the Silverpoint album.",
    ),
    Page(
        "Looks",
        "Pick a look",
        "The button right of the shutter shows your scene through every look. Star the ones you use: they stay in the quick row under the viewfinder, one tap away.",
    ),
    Page(
        "Shooting",
        "Photo or Pro",
        "Swipe the viewfinder or tap PHOTO and PRO. In Pro mode each setting has a dial you can feel, a tick for every stop. In low light Silverpoint merges several frames for cleaner photos.",
    ),
    Page(
        "If something looks wrong",
        "Diagnostics",
        "Settings → Diagnostics checks the camera and the GPU developer and saves a report you can send. If the viewfinder misbehaves, turn off the live recipe preview in Settings.",
    ),
)

/** What the introduction is showing: the start-up film, the reveal into the first page, or the pages. */
private enum class Phase { Film, Reveal, Pages }

/** The start-up film's mark ends at this fraction of the film's width (motion/src/Intro.tsx, MARK_FRACTION). */
private const val FILM_MARK_FRACTION = 0.2f

/** When the film's centre point ignites (frame 112 of 60 fps), for the haptic. */
private const val IGNITE_MS = 1867L

/** How long the mark takes to move from the film into the first page. */
private const val REVEAL_MS = 1100

/**
 * First-launch introduction. With [playStartup], it opens with the start-up film (made with
 * Remotion, motion/): silver grains swirl into Silverpoint's mark and its centre ignites. The film
 * plays centred on black, so its last frame is the mark at the centre of the screen; the same mark,
 * drawn natively, then glides up into the first page while the silver glow and the page fade in.
 * Tap the film to skip it. With animations turned off (accessibility), the pages show at once.
 */
@Composable
fun IntroScreen(onDone: () -> Unit, playStartup: Boolean = true) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val animationsOff = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var phase by rememberSaveable {
        mutableStateOf(
            when {
                !playStartup || animationsOff -> Phase.Pages
                inspection -> Phase.Reveal // previews and tests cannot play video
                else -> Phase.Film
            },
        )
    }
    val reveal = remember { Animatable(if (phase == Phase.Pages) 1f else 0f) }
    LaunchedEffect(phase) {
        if (phase == Phase.Reveal) {
            reveal.animateTo(1f, tween(REVEAL_MS, easing = FastOutSlowInEasing))
            phase = Phase.Pages
        }
    }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val t = reveal.value

    BoxWithConstraints(Modifier.fillMaxSize().background(Sl.Black)) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // The mark: where the film leaves it (centre, a fifth of the width out), then its place on the page.
        val endY = h * 0.25f
        val endR = with(density) { 44.dp.toPx() }
        val markY = lerp(h / 2f, endY, t)
        val markR = lerp(w * FILM_MARK_FRACTION, endR, t)

        if (phase != Phase.Film) {
            Canvas(Modifier.fillMaxSize()) {
                silverGlow(Offset(w / 2f, markY), w, alpha = stage(t, 0.25f, 0.75f))
                with(SilverpointMark) { drawMark(Offset(w / 2f, markY), markR) }
            }
        } else {
            StartupFilm(
                Modifier.size(with(density) { w.toDp() }).align(Alignment.Center),
                onEnd = { phase = Phase.Reveal },
            )
        }

        if (phase != Phase.Film) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 20.dp)) {
                Spacer(Modifier.weight(1f))
                // The page arrives in steps after the mark: kicker, title, text, then the buttons.
                AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") { p ->
                    val pg = PAGES[p]
                    Column {
                        Text(pg.kicker.uppercase(), style = LabelCaps.copy(fontSize = 12.sp, letterSpacing = 2.sp), color = Sl.Accent, modifier = Modifier.rise(stage(t, 0.45f, 0.8f)))
                        Spacer(Modifier.height(10.dp))
                        Text(pg.title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.rise(stage(t, 0.52f, 0.87f)))
                        Spacer(Modifier.height(14.dp))
                        Text(pg.body, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFD6D6DC), modifier = Modifier.rise(stage(t, 0.6f, 0.95f)))
                    }
                }
                Spacer(Modifier.height(36.dp))
                Row(Modifier.fillMaxWidth().rise(stage(t, 0.68f, 1f)), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDone) { Text("Skip", color = Sl.TextDim) }
                    Spacer(Modifier.weight(1f))
                    PageDots(PAGES.size, page)
                    Spacer(Modifier.weight(1f))
                    AccentPill(if (page < PAGES.lastIndex) "Next" else "Start") { if (page < PAGES.lastIndex) page++ else onDone() }
                }
            }
        }
    }
}

/** 0 before [from], 1 after [to], linear between: one element's share of the reveal. */
private fun stage(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

/** Fades in and rises 18 dp as [p] goes from 0 to 1. */
private fun Modifier.rise(p: Float) = graphicsLayer {
    alpha = p
    translationY = (1f - p) * 18.dp.toPx()
}

/**
 * Plays the start-up film (res/raw/intro_motion.mp4, square, on black) and calls [onEnd] when it
 * finishes, fails, or is tapped. The TextureView is transparent until the first frame, so the black
 * behind it shows rather than a flash. The phone gives a confirming tap as the centre ignites.
 */
@Composable
private fun StartupFilm(modifier: Modifier, onEnd: () -> Unit) {
    val end by rememberUpdatedState(onEnd)
    val haptics = rememberHaptics()
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(started) {
        if (started) {
            delay(IGNITE_MS)
            haptics.confirm()
        }
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { end() } }) {
        AndroidView(
            modifier = modifier.align(Alignment.Center),
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var player: MediaPlayer? = null
                        private var surface: Surface? = null

                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            val s = Surface(st).also { surface = it }
                            val p = runCatching { MediaPlayer.create(ctx, R.raw.intro_motion) }.getOrNull()
                            if (p == null) {
                                end()
                                return
                            }
                            player = p
                            p.setSurface(s)
                            p.setOnCompletionListener { end() }
                            p.setOnErrorListener { _, _, _ -> end(); true }
                            p.start()
                            started = true
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            player?.release()
                            player = null
                            surface?.release()
                            surface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
        )
    }
}

/** One UI's page indicator: the current page is a longer pill. */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            val w by animateDpAsState(if (i == current) 20.dp else 7.dp, label = "dot")
            Box(Modifier.width(w).height(7.dp).clip(CircleShape).background(if (i == current) Sl.Accent else Color(0xFF3A3A3F)))
        }
    }
}

/** A cool silver glow around the mark, with a lens's faint rings in it. */
private fun DrawScope.silverGlow(c: Offset, width: Float, alpha: Float) {
    if (alpha <= 0f) return
    drawRect(
        Brush.radialGradient(
            0f to Color(0xFFB8C7DA).copy(alpha = 0.30f * alpha),
            0.4f to Color(0xFF5B6B80).copy(alpha = 0.16f * alpha),
            1f to Color.Transparent,
            center = c,
            radius = width * 0.8f,
        ),
    )
    for ((k, r) in listOf(0.2f, 0.29f, 0.39f).withIndex()) {
        drawCircle(Color.White.copy(alpha = (0.12f - k * 0.03f) * alpha), radius = width * r, center = c, style = Stroke(1.2.dp.toPx()))
    }
}
