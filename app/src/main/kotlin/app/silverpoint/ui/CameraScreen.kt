package app.silverpoint.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.silverpoint.CameraMath
import app.silverpoint.CameraViewModel
import app.silverpoint.MergeMode
import app.silverpoint.RecipeSwatch
import app.silverpoint.camera.ExposureMath
import kotlinx.coroutines.delay

/** The camera: binds [CameraViewModel] to [CameraLayout]. */
@Composable
fun CameraScreen(vm: CameraViewModel, onSettings: () -> Unit) {
    val context = LocalContext.current
    val settings = vm.settings
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var tilt by remember { mutableFloatStateOf(0f) }
    var deviceAngle by remember { mutableIntStateOf(0) }
    val openSettings by rememberUpdatedState(onSettings)

    LifecycleResumeEffect(Unit) {
        vm.onResumed(true)
        vm.refreshOverlays()
        onPauseOrDispose { vm.onResumed(false) }
    }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                vm.deviceDegrees = orientation
                // Only the level line reads these; updating them with the level off would recompose the
                // whole screen many times a second while the phone is held.
                if (settings.level) {
                    tilt = CameraMath.tiltFromLevel(orientation)
                    deviceAngle = orientation
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    val recipes = remember(vm.recipes) {
        vm.recipes.map { r ->
            RecipeUi(r.name, r.look, r.bestFor, r.set, r.group, RecipeSwatch.of(r.params).map { Color(it) }, mono = r.params.grayscale)
        }
    }
    val lens = vm.lens
    val live = vm.live
    val thumbnail = remember(vm.thumbnail) { vm.thumbnail?.asImageBitmap() }
    val lookThumbs = remember(vm.lookThumbs) { vm.lookThumbs.mapValues { it.value.asImageBitmap() } }
    // Worked out once per lens, not on every recomposition (each reads the camera characteristics).
    val lensUis = remember(vm.lenses) { vm.lenses.map { LensUi(it.id, it.buttonText) } }
    val stops = remember(lens) {
        lens?.let { l -> ExposureMath.isoStops(l.isoRange.first, l.isoRange.last) to ExposureMath.shutterStops(l.exposureRange.first, l.exposureRange.last) }
            ?: (emptyList<Int>() to emptyList<Long>())
    }
    val ui = CameraUi(
        permission = granted,
        recipes = recipes,
        recipe = vm.recipe?.name,
        favourites = settings.favourites,
        lookThumbs = lookThumbs,
        lenses = lensUis,
        lens = lens?.id,
        pro = settings.proMode,
        proValues = lens?.let { l ->
            ProUi(
                isoStops = stops.first,
                iso = vm.manualIso,
                liveIso = live?.iso,
                shutterStops = stops.second,
                shutterNs = vm.manualShutterNs,
                liveShutterNs = live?.exposureNs,
                hasAutofocus = l.hasAutofocus,
                maxFocus = l.minFocusDiopters,
                focus = vm.manualFocus,
                liveFocus = live?.focusDiopters,
                kelvin = vm.wbKelvin,
                tint = vm.wbTint,
                fullManual = vm.fullManual,
            )
        },
        evThirds = vm.evThirds,
        flash = settings.flash,
        hasFlash = lens?.hasFlash ?: true,
        aspect = settings.aspect,
        grid = settings.grid,
        histogramOn = settings.histogram,
        zebras = settings.zebras,
        peaking = settings.peaking,
        level = settings.level,
        timerSeconds = settings.timerSeconds,
        histogram = vm.histogram,
        focusPoint = vm.focusPoint,
        focusLocked = live?.afState == android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
        countdown = vm.countdown,
        busy = vm.busy,
        busyLabel = vm.busyLabel,
        developing = vm.developing,
        merge = settings.merge,
        message = vm.message,
        shots = vm.shots,
        thumbnail = thumbnail,
        plainPreview = !vm.glPreview,
        tilt = tilt,
        deviceAngle = deviceAngle,
    )
    val actions = remember(vm) {
        CameraActions(
            onShutter = vm::shutter,
            onSettings = { openSettings() },
            onGallery = {
                vm.lastPhoto?.let { uri -> context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            },
            onRequestPermission = { permission.launch(Manifest.permission.CAMERA) },
            onRecipe = vm::selectRecipe,
            onToggleFavourite = vm::toggleFavourite,
            onLens = { id -> vm.lenses.firstOrNull { it.id == id }?.let(vm::selectLens) },
            onProMode = vm::setProMode,
            onEv = vm::setEv,
            onIso = vm::setIso,
            onShutterSpeed = vm::setShutter,
            onFocus = vm::setFocus,
            onWhiteBalance = { k, t -> vm.setWhiteBalance(k, t) },
            onFlash = vm::setFlash,
            onAspect = vm::setAspect,
            onTimer = vm::setTimer,
            onTapFocus = vm::focusAt,
            onResetFocus = vm::resetFocus,
            onToggleGrid = { vm.settings.grid = !vm.settings.grid },
            onToggleHistogram = { vm.settings.histogram = !vm.settings.histogram; vm.refreshOverlays() },
            onToggleZebras = { vm.settings.zebras = !vm.settings.zebras; vm.refreshOverlays() },
            onTogglePeaking = { vm.settings.peaking = !vm.settings.peaking; vm.refreshOverlays() },
            onToggleLevel = { vm.settings.level = !vm.settings.level },
            onDismissMessage = vm::clearMessage,
        )
    }
    val liveLooks = remember(vm) {
        object : LiveLooks {
            override fun attach(key: Any, surface: Surface, width: Int, height: Int, look: String, radius: Float) =
                vm.attachThumb(key, surface, width, height, look, radius)
            override fun resize(key: Any, width: Int, height: Int) = vm.resizeThumb(key, width, height)
            override fun detach(key: Any) = vm.detachThumb(key)
        }
    }
    CompositionLocalProvider(LocalLiveLooks provides liveLooks.takeIf { vm.liveLooks }) {
        CameraLayout(ui, actions) {
            if (granted && lens != null) {
                val gl = vm.glPreview
                key(gl, lens.previewSize) {
                    if (gl) {
                        // Portrait buffer: the renderer draws the upright image itself.
                        Viewfinder(lens.previewSize.height, lens.previewSize.width) { s, w, h -> vm.onSurface(s, w, h, forGl = true) }
                    } else {
                        Viewfinder(lens.previewSize.width, lens.previewSize.height) { s, w, h -> vm.onSurface(s, w, h, forGl = false) }
                    }
                }
            }
        }
    }
}

/**
 * The camera screen. Top to bottom: the top bar (settings and the four between-shot controls),
 * then the viewfinder as wide as the phone in the chosen shape, then the controls anchored to the
 * bottom. Where the controls have to overlap the picture (Pro mode's dial, 9:16), they sit on a
 * soft fade instead of a hard card, so the picture stays readable. [viewfinder] draws the camera
 * picture (3:4); [initial] opens a dial, top-bar row or the look selector from the start (screenshots).
 */
@Composable
fun CameraLayout(ui: CameraUi, act: CameraActions, initial: Initial = Initial(), viewfinder: @Composable () -> Unit) {
    var dial by rememberSaveable { mutableStateOf(initial.dial) }
    var top by remember { mutableStateOf(initial.top) }
    var looks by remember { mutableStateOf(initial.looks) }
    val haptics = rememberHaptics()

    // Pro mode's dials belong to Pro mode; Photo mode keeps only its exposure dial.
    LaunchedEffect(ui.pro) { if (!ui.pro && dial != DialParam.Ev) dial = null }
    // Felt, not only seen: the photo is taken, focus has locked, something failed, the timer ticks.
    OnChange(ui.shots) { haptics.confirm() }
    OnChange(ui.focusLocked) { if (it) haptics.confirm() }
    OnChange(ui.message) { if (it != null) haptics.reject() }
    OnChange(ui.countdown) { if (it > 0) haptics.majorDetent() }
    // The look's name, shown over the picture for a moment when it changes.
    var lookName by remember { mutableStateOf<String?>(null) }
    var lookNameOn by remember { mutableStateOf(false) }
    OnChange(ui.recipe) { lookName = it; lookNameOn = it != null }
    LaunchedEffect(lookName, lookNameOn) { if (lookNameOn) { delay(1600); lookNameOn = false } }

    Column(Modifier.fillMaxSize().background(Sl.Black).systemBarsPadding()) {
        TopBar(ui, act, top) { top = it }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val f = CameraMath.framing(maxWidth.value, maxHeight.value, ui.aspect.ratio)
            val density = LocalDensity.current.density
            val vis = Rect(Offset(f.visX, f.visY), androidx.compose.ui.geometry.Size(f.visW, f.visH))

            // The camera picture, possibly larger than the screen (9:16 crops its sides off-screen).
            Box(Modifier.offset(f.frameX.dp, f.frameY.dp).wrapContentSize(unbounded = true, align = Alignment.TopStart)) {
                Box(Modifier.requiredSize(f.frameW.dp, f.frameH.dp).background(Color(0xFF0B0B0D))) { viewfinder() }
            }

            // Everything that belongs to the picture, inside what the photo will keep.
            Box(Modifier.offset(f.visX.dp, f.visY.dp).size(f.visW.dp, f.visH.dp)) {
                if (!ui.permission) {
                    PermissionCard(act.onRequestPermission, Modifier.align(Alignment.Center))
                } else {
                    ViewfinderGestures(ui, act, f)
                    if (ui.grid) GridOverlay()
                    if (ui.level) LevelOverlay(ui.tilt, ui.deviceAngle)
                    ui.focusPoint?.let { (u, v) ->
                        // The focus point is kept in whole-picture coordinates; show it where it is in the crop.
                        val x = (f.frameX + u * f.frameW - f.visX) / f.visW
                        val y = (f.frameY + v * f.frameH - f.visY) / f.visH
                        if (x in 0f..1f && y in 0f..1f) FocusMark(x, y, ui.focusLocked)
                    }
                }
                if (ui.countdown > 0) CountdownText(ui.countdown, Modifier.align(Alignment.Center))
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                    if (ui.histogramOn && ui.permission) ui.histogram?.let { HistogramView(it) }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ui.busyLabel?.let { Badge(it, dot = Sl.Accent) }
                        if (ui.plainPreview && ui.permission) Badge("Plain preview", color = Sl.TextDim)
                        if (ui.merge == MergeMode.Always) Badge("Merge always", color = Sl.TextDim)
                    }
                }
                ui.message?.let { MessagePill(it, act.onDismissMessage, Modifier.align(Alignment.TopCenter).padding(top = 64.dp)) }
                val nameAlpha by animateFloatAsState(if (lookNameOn && ui.message == null) 1f else 0f, label = "lookName")
                if (nameAlpha > 0f) {
                    Text(
                        lookName ?: "", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        style = TextStyle(shadow = Shadow(Color(0x99000000), blurRadius = 12f)),
                        modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = nameAlpha },
                    )
                }
            }
            FrameMask(
                Rect(Offset(vis.left * density, vis.top * density), androidx.compose.ui.geometry.Size(vis.width * density, vis.height * density)),
                22.dp,
            )

            // The controls, anchored to the bottom; a fade where they cross the picture. With a dial
            // open its header and scale cross the picture, so the fade darkens sooner (eased, not switched).
            val dark by animateFloatAsState(if (dial != null) 1f else 0f, tween(DECK_MS, easing = FastOutSlowInEasing), label = "fade")
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            lerp(0.2f, 0.1f, dark) to Color.Black.copy(alpha = lerp(0.4f, 0.65f, dark)),
                            lerp(0.45f, 0.35f, dark) to Color.Black.copy(alpha = lerp(0.85f, 0.9f, dark)),
                            lerp(0.65f, 0.55f, dark) to Sl.Black,
                        ),
                    ),
            ) {
                // One slot for the lens row and, in Pro mode, the open dial in its place: they swap in a
                // single motion (crossfade while the slot's height eases), so nothing below moves and
                // nothing is drawn twice. Switching between dials crossfades them in place.
                AnimatedContent(
                    targetState = if (ui.pro) dial else null,
                    transitionSpec = { deckSwap() },
                    contentAlignment = Alignment.BottomCenter,
                    label = "slot",
                ) { open ->
                    if (open == null) {
                        ViewfinderRow(ui, evOpen = dial == DialParam.Ev && !ui.pro, onEv = { dial = if (dial == DialParam.Ev) null else DialParam.Ev }, act = act)
                    } else {
                        DialPanel(open, ui, act)
                    }
                }
                AnimatedContent(
                    targetState = when {
                        ui.pro -> "pro"
                        dial == DialParam.Ev -> "ev"
                        else -> "looks"
                    },
                    transitionSpec = { deckSwap() },
                    contentAlignment = Alignment.BottomCenter,
                    label = "deck",
                ) { deck ->
                    when (deck) {
                        "pro" -> ProDeck(ui, dial) { dial = it }
                        "ev" -> DialPanel(DialParam.Ev, ui, act)
                        else -> LookBar(ui, act.onRecipe, act.onToggleFavourite) { looks = true }
                    }
                }
                ModeTabs(ui.pro) { act.onProMode(it) }
                ShutterRow(ui, act) { looks = true }
            }
        }
    }

    if (looks) {
        LookSheet(
            recipes = ui.recipes,
            selected = ui.recipe,
            favourites = ui.favourites,
            thumbs = ui.lookThumbs,
            onSelect = { act.onRecipe(it); looks = false },
            onToggleFavourite = act.onToggleFavourite,
            onDismiss = { looks = false },
        )
    }
}

/** How long the deck takes to swap a row for another (a lens row for a dial, looks for the EV dial). */
private const val DECK_MS = 240

/**
 * The deck's one transition: the old row fades out quickly, the new one fades in just after, and
 * the height eases between them on the same curve, so a swap reads as one movement.
 */
private fun <S> AnimatedContentTransitionScope<S>.deckSwap(): ContentTransform =
    fadeIn(tween(DECK_MS - 80, delayMillis = 80, easing = LinearOutSlowInEasing)) togetherWith
        fadeOut(tween(90, easing = FastOutLinearInEasing)) using
        SizeTransform(clip = true) { _, _ -> tween(DECK_MS, easing = FastOutSlowInEasing) }

/** What the camera screen opens with; screenshot tests use it to show a dial or a row. */
data class Initial(val dial: DialParam? = null, val top: TopOption? = null, val looks: Boolean = false)

/** Runs [onChange] when [value] changes after the first composition (not for the value it starts with). */
@Composable
private fun <T> OnChange(value: T, onChange: (T) -> Unit) {
    val first = remember { mutableStateOf(true) }
    val latest by rememberUpdatedState(onChange)
    LaunchedEffect(value) {
        if (first.value) first.value = false else latest(value)
    }
}

/** Tap to focus and meter, double tap to reset, swipe sideways to switch between PHOTO and PRO. */
@Composable
private fun ViewfinderGestures(ui: CameraUi, act: CameraActions, f: CameraMath.Framing) {
    val pro by rememberUpdatedState(ui.pro)
    val framing by rememberUpdatedState(f)
    val swipe = with(LocalDensity.current) { 72.dp.toPx() }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    // Focus and metering work on the whole camera picture, so map from the crop.
                    onTap = { o ->
                        val (u, v) = framing.toFrame(o.x / size.width, o.y / size.height)
                        act.onTapFocus(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
                    },
                    onDoubleTap = { act.onResetFocus() },
                )
            }
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        // Like Samsung's camera: swipe left for the mode on the right.
                        if (dx < -swipe && !pro) act.onProMode(true)
                        if (dx > swipe && pro) act.onProMode(false)
                    },
                ) { change, amount ->
                    change.consume()
                    dx += amount
                }
            },
    )
}

/** A SurfaceView whose buffer is fixed to [width] x [height]; reports the surface once it has that size. */
@Composable
private fun Viewfinder(width: Int, height: Int, onSurface: (Surface?, Int, Int) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.setFixedSize(width, height)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) = Unit

                    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
                        // Only hand the surface over once it has the size the camera session expects.
                        if (w == width && hgt == height) onSurface(h.surface, w, hgt)
                    }

                    override fun surfaceDestroyed(h: SurfaceHolder) = onSurface(null, 0, 0)
                })
            }
        },
    )
}
