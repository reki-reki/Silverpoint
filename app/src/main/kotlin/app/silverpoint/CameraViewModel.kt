package app.silverpoint

import android.app.Application
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.hardware.camera2.CameraMetadata
import app.silverpoint.camera.CameraController
import app.silverpoint.camera.Controls
import app.silverpoint.camera.ExposureMath
import app.silverpoint.camera.Flash
import app.silverpoint.camera.Focus
import app.silverpoint.camera.Lens
import app.silverpoint.camera.PreviewState
import app.silverpoint.camera.RawCapture
import app.silverpoint.camera.StillExposure
import app.silverpoint.pipeline.ColorMath
import app.silverpoint.pipeline.AndroidRawMetadata
import app.silverpoint.pipeline.DevelopParams
import app.silverpoint.pipeline.Grain
import app.silverpoint.pipeline.LookLut
import app.silverpoint.pipeline.LookThumbnails
import app.silverpoint.pipeline.Merge
import app.silverpoint.pipeline.PreviewRenderer
import app.silverpoint.recipes.Recipe
import app.silverpoint.recipes.RecipeStore
import app.silverpoint.storage.DngSaver
import app.silverpoint.storage.PhotoInfo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Camera screen logic: keeps the camera open while the screen is visible, draws the live preview
 * with the selected recipe, takes RAW stills, develops them with the recipe and saves them to the Gallery.
 */
class CameraViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val manager = app.getSystemService(CameraManager::class.java)
    private val controller = CameraController(manager)
    private val cameraThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val lookThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    private val developer = (app as SilverpointApp).developer
    @Volatile
    private var renderer: PreviewRenderer? = null

    /** Baked preview tables by recipe name. Baking takes a few hundred milliseconds. */
    private val previewLooks = ConcurrentHashMap<String, LookLut>()

    val recipes: List<Recipe> = runCatching { RecipeStore.load(app) }.getOrDefault(emptyList())

    val recipe: Recipe?
        get() = recipes.firstOrNull { it.name == settings.recipe } ?: recipes.firstOrNull()

    var lens by mutableStateOf<Lens?>(null)
        private set
    var lenses by mutableStateOf<List<Lens>>(emptyList())
        private set

    /** Pro mode values; null means automatic. Shutter in nanoseconds, focus in diopters, WB in kelvin. */
    var manualIso by mutableStateOf<Int?>(null)
        private set
    var manualShutterNs by mutableStateOf<Long?>(null)
        private set
    var manualFocus by mutableStateOf<Float?>(null)
        private set
    var wbKelvin by mutableStateOf<Int?>(null)
        private set
    var wbTint by mutableIntStateOf(0)
        private set

    /** Where the user tapped to focus and meter (upright viewfinder, 0..1), or null. */
    var focusPoint by mutableStateOf<Pair<Float, Float>?>(null)
        private set

    /** What the camera is doing now (ISO, shutter, focus), about ten times a second. */
    var live by mutableStateOf<PreviewState?>(null)
        private set

    /** Seconds left on the self-timer, or 0. */
    var countdown by mutableIntStateOf(0)
        private set
    private var timerJob: Job? = null
    var evThirds by mutableIntStateOf(0)
        private set
    /** True while the camera is taking or merging a photo (the shutter waits). */
    var busy by mutableStateOf(false)
        private set
    /** "merging 6 frames…" while a burst is merged. */
    var busyLabel by mutableStateOf<String?>(null)
        private set
    /** Photos waiting to be developed. */
    var developing by mutableIntStateOf(0)
        private set
    var thumbnail by mutableStateOf<Bitmap?>(null)
        private set
    var lastPhoto by mutableStateOf<Uri?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var lastTiming by mutableStateOf<String?>(null)
        private set
    var histogram by mutableStateOf<IntArray?>(null)
        private set

    /** Photos captured this session; the screen confirms each with a haptic. */
    var shots by mutableIntStateOf(0)
        private set

    /**
     * The bundled sample picture through each look, by recipe name: the look selector's thumbnails
     * when live previews are off, and under the live ones until their first frame.
     */
    var lookThumbs by mutableStateOf<Map<String, Bitmap>>(emptyMap())
        private set

    /** True while the GL preview runs, so the look thumbnails can be drawn live from it. */
    private var rendererRunning by mutableStateOf(false)

    /** Whether the look thumbnails are live camera feeds (Settings → Live look previews, and the GL preview running). */
    val liveLooks: Boolean get() = settings.liveLookPreviews && rendererRunning

    /** Thumbnail-sized look tables by recipe name, baked once. */
    private val thumbLuts = ConcurrentHashMap<String, LookLut>()

    /** A live thumbnail's surface on screen (see [attachThumb]). */
    private class ThumbSurface(val surface: Surface, val width: Int, val height: Int, val look: String, val radius: Float)

    /** The live thumbnails on screen, kept so a new renderer (lens change, resume) can pick them up. */
    private val thumbSurfaces = ConcurrentHashMap<Any, ThumbSurface>()

    /** True once the GL preview failed on this phone; the plain camera preview is used instead. */
    var glPreviewFailed by mutableStateOf(false)
        private set

    /** Whether the viewfinder surface should be set up for the GL preview (portrait buffer) or the plain one. */
    val glPreview: Boolean get() = settings.livePreview && !glPreviewFailed

    /** Phone orientation from `OrientationEventListener`, used to make photos upright. */
    @Volatile
    var deviceDegrees = 0

    @Volatile
    private var surface: Surface? = null
    @Volatile
    private var surfaceSize = 0 to 0
    @Volatile
    private var surfaceIsGl = false
    @Volatile
    private var resumed = false
    @Volatile
    private var opened = false
    /** The viewfinder surface the open camera session draws into (directly or through the renderer). */
    private var openedWith: Surface? = null
    /** The surface the camera itself writes to: the renderer's input or the viewfinder. */
    private var cameraTarget: Surface? = null
    private var openedLens: Lens? = null

    init {
        // The camera was taken away or failed: release everything (renderer included) so the next
        // resume or surface change opens it cleanly.
        controller.onError = { msg ->
            viewModelScope.launch(cameraThread) {
                closeCamera()
                withContext(Dispatchers.Main) { message = msg }
            }
        }
        controller.onPreviewState = { st -> viewModelScope.launch(Dispatchers.Main) { live = st } }
        viewModelScope.launch { developer.pending.collect { developing = it } }
        // The last saved photo comes from a state that outlives this screen (see Developer.lastSaved);
        // failures are only shown to the screen that is open when they happen.
        viewModelScope.launch {
            developer.lastSaved.collect { o ->
                if (o != null) {
                    thumbnail = o.thumbnail
                    lastPhoto = o.uri
                    lastTiming = o.summary
                }
            }
        }
        viewModelScope.launch {
            developer.outcomes.collect { o -> if (o is DevelopOutcome.Failed) message = o.message }
        }
        viewModelScope.launch(cameraThread) {
            loadLenses()
            withContext(Dispatchers.Main) { sync() }
        }
        // Warm the look tables for the selected recipe, then make the still look thumbnails.
        recipe?.let { r -> viewModelScope.launch(lookThread) { developer.look(r); previewLook(r) } }
        // Off the look thread, so the viewfinder's own look is never queued behind 26 thumbnail tables.
        viewModelScope.launch(Dispatchers.Default) { makeSampleThumbs() }
    }

    /** False until the lenses have been read with the camera permission granted (see [loadLenses]). */
    @Volatile
    private var lensesComplete = false

    /**
     * Reads the lenses. Without the camera permission Android leaves the colour matrices and the
     * focus range out of the characteristics, so lenses read before the permission was granted
     * (first launch) are read again once it is; otherwise every photo of that session would fail.
     */
    private suspend fun loadLenses() {
        val permitted = getApplication<Application>().checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val found = runCatching { Lens.all(manager) }
        withContext(Dispatchers.Main) {
            found.onSuccess { all ->
                lenses = all
                lens = all.firstOrNull { it.id == settings.lensId } ?: all.firstOrNull { it.label?.kind == "main" } ?: all.firstOrNull()
                lensesComplete = permitted
            }.onFailure { message = "No usable RAW camera: ${it.message}" }
        }
    }


    /** The viewfinder surface, its buffer size and whether it was set up for the GL preview. */
    fun onSurface(s: Surface?, width: Int = 0, height: Int = 0, forGl: Boolean = false) {
        if (s != null && s == surface && surfaceIsGl == forGl) return
        surface = s
        surfaceSize = width to height
        surfaceIsGl = forGl
        sync()
    }

    fun onResumed(isResumed: Boolean) {
        resumed = isResumed
        sync()
    }

    private fun thumbLut(r: Recipe): LookLut = thumbLuts.getOrPut(r.name) { LookThumbnails.lut(r.params) }

    /** The bundled sample picture through every look, starred looks first. */
    private suspend fun makeSampleThumbs() {
        val sample = runCatching {
            getApplication<Application>().assets.open(SAMPLE_ASSET).use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return
        val w = sample.width
        val h = sample.height
        val argb = IntArray(w * h).also { sample.getPixels(it, 0, w, 0, 0, w, h) }
        val order = recipes.sortedByDescending { it.name == settings.recipe || it.name in settings.favourites }
        val made = HashMap<String, Bitmap>()
        for ((k, recipe) in order.withIndex()) {
            made[recipe.name] = Bitmap.createBitmap(LookThumbnails.apply(argb, thumbLut(recipe)), w, h, Bitmap.Config.ARGB_8888)
            if (k % 6 == 5 || k == order.lastIndex) {
                val batch = HashMap(made)
                withContext(Dispatchers.Main) { lookThumbs = lookThumbs + batch }
                made.clear()
            }
        }
    }

    /**
     * Draws the camera live through [look] into [surface] (a look card's TextureView, [width] x
     * [height] pixels, corners of [radius] pixels) while the GL preview runs. [key] identifies the
     * card for [resizeThumb] and [detachThumb].
     */
    fun attachThumb(key: Any, surface: Surface, width: Int, height: Int, look: String, radius: Float) {
        thumbSurfaces[key] = ThumbSurface(surface, width, height, look, radius)
        renderer?.attachThumb(key, surface, width, height, look, radius)
    }

    fun resizeThumb(key: Any, width: Int, height: Int) {
        val t = thumbSurfaces[key] ?: return
        thumbSurfaces[key] = ThumbSurface(t.surface, width, height, t.look, t.radius)
        renderer?.resizeThumb(key, width, height)
    }

    /** Stops drawing into [key]'s surface; returns once it is safe to release. */
    fun detachThumb(key: Any) {
        thumbSurfaces.remove(key)
        renderer?.detachThumb(key)
    }

    fun setFlash(mode: FlashMode) {
        settings.flash = mode
        pushControls()
    }

    fun setAspect(aspect: AspectRatio) {
        settings.aspect = aspect
    }

    fun setTimer(seconds: Int) {
        settings.timerSeconds = seconds
    }

    fun setEv(thirds: Int) {
        evThirds = thirds.coerceIn(-CameraMath.EV_THIRDS_MAX, CameraMath.EV_THIRDS_MAX)
        pushControls()
    }

    /** Full manual exposure: both ISO and shutter set in Pro mode, so the EV dial does nothing. */
    val fullManual: Boolean get() = settings.proMode && manualIso != null && manualShutterNs != null

    fun setProMode(on: Boolean) {
        settings.proMode = on
        pushControls()
    }

    fun setIso(iso: Int?) {
        manualIso = iso
        pushControls()
    }

    fun setShutter(ns: Long?) {
        manualShutterNs = ns
        pushControls()
    }

    fun setFocus(diopters: Float?) {
        manualFocus = diopters
        if (diopters != null) focusPoint = null
        pushControls()
    }

    fun setWhiteBalance(kelvin: Int?, tint: Int = wbTint) {
        wbKelvin = kelvin
        wbTint = tint
        pushControls()
    }

    /** Tap on the viewfinder: focus and meter there (unless focus is manual). */
    fun focusAt(u: Float, v: Float) {
        if (settings.proMode && manualFocus != null) {
            focusPoint = u to v
            pushControls()
            return
        }
        focusPoint = u to v
        pushControls()
        if (lens?.hasAutofocus == true) controller.focusAt(u, v)
    }

    /** Double tap: back to continuous autofocus and whole-frame metering. */
    fun resetFocus() {
        focusPoint = null
        pushControls()
    }

    fun selectLens(l: Lens) {
        if (l.id == lens?.id) return
        val newSize = l.previewSize != lens?.previewSize
        settings.lensId = l.id
        lens = l
        // A different preview size rebuilds the viewfinder; wait for its new surface instead of
        // reopening on the old one.
        if (newSize) surface = null
        focusPoint = null
        manualFocus = null
        live = null
        sync()
    }

    /** Sends exposure, focus, metering and white balance to the camera. */
    private fun pushControls() {
        val l = lens ?: return
        controller.setControls(controlsFor(l))
    }

    private fun controlsFor(l: Lens): Controls {
        val pro = settings.proMode
        val point = focusPoint
        val focus = when {
            pro && manualFocus != null -> Focus.Manual(manualFocus!!)
            point != null -> Focus.Point(point.first, point.second)
            else -> Focus.Continuous
        }
        return Controls(
            aeCompensation = aeSteps(l),
            iso = if (pro) manualIso else null,
            exposureNs = if (pro) manualShutterNs else null,
            focus = focus,
            awbMode = if (pro) CameraMath.awbModeFor(wbKelvin) else CameraMetadata.CONTROL_AWB_MODE_AUTO,
            meteringPoint = point,
            flash = if (!l.hasFlash) Flash.Off else when (settings.flash) {
                FlashMode.Off -> Flash.Off
                FlashMode.Auto -> Flash.Auto
                FlashMode.On -> Flash.On
            },
        )
    }

    /** Exposure for the next still: manual or emulated priority in Pro mode, else null (auto). */
    private fun stillExposure(l: Lens): StillExposure? {
        if (!settings.proMode) return null
        val st = live
        return ExposureMath.forCapture(manualIso, manualShutterNs, st?.iso, st?.exposureNs, l.isoRange, l.exposureRange)
            ?.let { (iso, ns) -> StillExposure(iso, ns) }
    }

    fun selectRecipe(name: String) {
        if (recipes.none { it.name == name }) return
        settings.recipe = name
        applyPreviewStyle()
        // Skipped if another look was picked meanwhile (flicking through the quick row); the developer
        // still bakes the table on demand when a photo is taken.
        recipe?.let { r -> viewModelScope.launch(lookThread) { if (recipe?.name == r.name) developer.look(r) } }
    }

    fun toggleFavourite(name: String) = settings.toggleFavourite(name)

    /** The user dismissed the notice on the viewfinder. */
    fun clearMessage() {
        message = null
    }

    /** Re-applies overlay settings (zebras, peaking, histogram) to the running preview. */
    fun refreshOverlays() {
        renderer?.let { r ->
            r.zebras = settings.zebras
            r.peaking = settings.peaking
            r.onHistogram = if (settings.histogram) ::postHistogram else null
        }
        if (!settings.histogram) histogram = null
    }

    fun setLivePreview(on: Boolean) {
        settings.livePreview = on
        if (on) glPreviewFailed = false
    }

    /** Shutter button: with the self-timer set, counts down first; pressing again cancels. */
    fun shutter() {
        if (countdown > 0) {
            timerJob?.cancel()
            countdown = 0
            return
        }
        val seconds = settings.timerSeconds
        if (seconds <= 0) {
            takePhoto()
            return
        }
        timerJob = viewModelScope.launch {
            for (left in seconds downTo 1) {
                countdown = left
                delay(1000)
            }
            countdown = 0
            takePhoto()
        }
    }

    /** Frames to merge for the next photo: 1 means a single shot. */
    private fun framesFor(exposure: StillExposure?): Int {
        val mode = settings.merge
        if (mode == MergeMode.Off) return 1
        val power = getApplication<Application>().getSystemService(PowerManager::class.java)
        if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return 1
        if (mode == MergeMode.Always) return MERGE_FRAMES
        val iso = exposure?.iso ?: live?.iso ?: return 1
        val ns = exposure?.exposureNs ?: live?.exposureNs ?: return 1
        return if (CameraMath.isLowLight(iso, ns)) MERGE_FRAMES else 1
    }

    private fun takePhoto() {
        val l = lens ?: return
        val r = recipe ?: return
        if (busy || !opened) return
        if (developing >= Developer.MAX_PENDING) {
            message = "Still developing the last photos…"
            return
        }
        busy = true
        message = null
        // Counts as pending from now, so the foreground service starts while the app is surely in front.
        developer.begin()
        val rotation = CameraMath.photoRotation(l.sensorOrientation, deviceDegrees)
        val exposure = stillExposure(l)
        val wb = if (settings.proMode) wbKelvin?.let { it to wbTint } else null
        val saveDng = settings.saveDng
        val ultraHdr = settings.ultraHdr
        val frames = framesFor(exposure)
        // Every frame of a merged burst gets the same exposure: the metered one unless Pro mode set
        // one. Auto-exposure moving mid-burst would make the merge reject most of a frame.
        val metered = live
        val meteredIso = metered?.iso
        val meteredNs = metered?.exposureNs
        val shotExposure = exposure ?: if (frames > 1 && meteredIso != null && meteredNs != null) {
            StillExposure(meteredIso.coerceIn(l.isoRange), meteredNs.coerceIn(l.exposureRange))
        } else null
        val playSound = settings.shutterSound
        val flash = l.hasFlash && settings.flash != FlashMode.Off
        val fullManual = settings.proMode && manualIso != null && manualShutterNs != null
        val aspect = settings.aspect.ratio
        // ATOMIC: once begin() has counted this photo, the block runs even if the screen goes away
        // first, so it always ends in submit() or abandon().
        viewModelScope.launch(cameraThread, start = CoroutineStart.ATOMIC) {
            val captures = try {
                // A flash lights one frame, so a flash photo is never merged, and it is exposed by
                // auto-exposure with the pre-flash unless ISO and shutter are both set by hand.
                val fire = flash && runCatching { controller.prepareFlash() }.getOrDefault(false)
                val n = if (fire) 1 else frames
                val e = if (fire) exposure.takeIf { fullManual } else shotExposure
                if (n == 1) listOf(controller.capture(e, fireFlash = fire)) else controller.captureBurst(n, e)
            } catch (t: Throwable) {
                developer.abandon()
                withContext(Dispatchers.Main) {
                    message = "Capture failed: ${t.message}"
                    busy = false
                }
                return@launch
            }
            if (playSound) runCatching { sound.play(MediaActionSound.SHUTTER_CLICK) }
            withContext(Dispatchers.Main) { shots++ }
            // Merging and copying run on the developer's merge thread so the viewfinder keeps going,
            // and outlive this screen so captured frames are always developed (or released).
            developer.prepare { prepareJob(captures, rotation, r, wb, saveDng, ultraHdr, aspect) }
        }
    }

    /**
     * Turns captured frames into a [DevelopJob]: writes the DNG if asked, merges bursts, copies the
     * samples out of the camera's buffers (releasing them) and hands the job to the [Developer].
     */
    private suspend fun prepareJob(captures: List<RawCapture>, rotation: Int, r: Recipe, wb: Pair<Int, Int>?, saveDng: Boolean, ultraHdr: Boolean, aspect: Float) {
        try {
            val first = captures.first()
            val img = first.image
            val takenAt = System.currentTimeMillis()
            val shot = AndroidRawMetadata.from(first.lens.characteristics, first.lensResult, img.width, img.height)
            val balanced = wb?.let { (k, t) -> shot.withNeutral(ColorMath.neutralFor(shot, k.toDouble(), t.toDouble())) } ?: shot
            val info = PhotoInfo(
                recipeName = r.name,
                takenAtMillis = takenAt,
                iso = first.lensResult.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureTimeNs = first.lensResult.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                fNumber = first.lensResult.get(CaptureResult.LENS_APERTURE) ?: first.lens.aperture,
                focalLengthMm = first.lensResult.get(CaptureResult.LENS_FOCAL_LENGTH) ?: first.lens.focalLengthMm,
                make = Build.MANUFACTURER,
                model = Build.MODEL,
                software = "Silverpoint ${BuildConfig.VERSION_NAME}",
                framesMerged = captures.size,
            )
            if (saveDng) {
                runCatching { DngSaver.save(getApplication(), first.lens.characteristics, first.lensResult, img, info, DngSaver.exifOrientation(rotation)) }
                    .onFailure { e -> withContext(Dispatchers.Main) { message = "DNG not saved: ${e.message}" } }
            }
            val job = if (captures.size == 1) {
                val plane = img.planes[0]
                val copy = ByteBuffer.allocateDirect(plane.buffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)
                copy.put(plane.buffer.duplicate())
                copy.position(0)
                val stride = plane.rowStride
                first.close()
                DevelopJob(copy, stride, balanced, info, rotation, r, frames = 1, ultraHdr = ultraHdr, aspect = aspect)
            } else {
                withContext(Dispatchers.Main) { busyLabel = "merging ${captures.size} frames…" }
                val start = SystemClock.elapsedRealtime()
                val noise = Merge.Noise.of(
                    first.lensResult.get(CaptureResult.SENSOR_NOISE_PROFILE)?.map { it.first to it.second },
                    first.lensResult.get(CaptureResult.SENSOR_SENSITIVITY),
                )
                val merged = MergeRunner.merge(captures, balanced, noise)
                val mergeMs = SystemClock.elapsedRealtime() - start
                val buf = ByteBuffer.allocateDirect(merged.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                buf.asShortBuffer().put(merged)
                buf.position(0)
                DevelopJob(buf, balanced.width * 2, balanced.withLevelsScaled(Merge.SCALE.toFloat()), info, rotation, r, captures.size, mergeMs, ultraHdr, aspect)
            }
            developer.submit(job)
        } catch (t: Throwable) {
            developer.abandon()
            withContext(Dispatchers.Main) { message = "Could not process the photo: ${t.message}" }
        } finally {
            captures.forEach { runCatching { it.close() } }
            withContext(Dispatchers.Main) {
                busy = false
                busyLabel = null
            }
        }
    }

    private fun previewLook(r: Recipe): LookLut = previewLooks.getOrPut(r.name) { PreviewRenderer.previewLook(r.params) }

    private fun applyPreviewStyle() {
        val r = recipe ?: return
        val target = renderer ?: return
        viewModelScope.launch(lookThread) {
            // Another look was picked while this one waited: bake only the latest.
            if (recipe?.name != r.name) return@launch
            val p = r.params
            val (vignetteStart, vignetteEnd) = DevelopParams.vignetteShape(p.vignetteMidpoint, p.vignetteFeather)
            target.setStyle(
                PreviewRenderer.Style(
                    look = previewLook(r),
                    vignette = (p.vignette / 100f).coerceIn(-1f, 1f),
                    vignetteStart = vignetteStart,
                    vignetteEnd = vignetteEnd,
                    grainAmount = Grain.amplitude(p.grainAmount),
                    grainSize = p.grainSize,
                    grainRoughness = (p.grainFrequency / 100f).coerceIn(0f, 1f),
                ),
            )
        }
    }

    private fun postHistogram(bins: IntArray) {
        viewModelScope.launch(Dispatchers.Main) { histogram = bins }
    }

    private fun aeSteps(l: Lens): Int = CameraMath.aeSteps(
        evThirds, l.aeCompensationStep.numerator, l.aeCompensationStep.denominator,
        l.aeCompensationRange.lower, l.aeCompensationRange.upper,
    )

    /** Opens the camera when the screen is resumed and the viewfinder surface exists; closes it otherwise. */
    private fun sync() {
        val wantOpen = resumed && surface?.isValid == true
        val s = surface
        val (sw, sh) = surfaceSize
        val gl = surfaceIsGl
        viewModelScope.launch(cameraThread) {
            if (wantOpen && !opened && !lensesComplete) loadLenses()
            val l = lens ?: return@launch
            // A different viewfinder surface (or none): let go of the old one first.
            if (opened && (!wantOpen || openedWith != s)) closeCamera()
            // Same viewfinder, another lens with the same preview size: only the session changes.
            if (opened && openedLens?.id != l.id) {
                val target = cameraTarget
                if (target != null && openedLens?.previewSize == l.previewSize) {
                    try {
                        controller.setControls(withContext(Dispatchers.Main) { controlsFor(l) })
                        controller.open(l, target)
                        openedLens = l
                    } catch (t: Throwable) {
                        closeCamera()
                        withContext(Dispatchers.Main) { message = "Could not switch lens: ${t.message}" }
                    }
                    return@launch
                }
                closeCamera()
            }
            if (wantOpen && !opened && s != null) {
                try {
                    val target = if (gl) startRenderer(s, sw, sh, l) ?: return@launch else s
                    controller.setControls(withContext(Dispatchers.Main) { controlsFor(l) })
                    controller.open(l, target)
                    opened = true
                    openedWith = s
                    cameraTarget = target
                    openedLens = l
                    withContext(Dispatchers.Main) { if (message?.startsWith("Camera") == true) message = null }
                } catch (t: Throwable) {
                    releaseRenderer()
                    withContext(Dispatchers.Main) { message = "Camera unavailable: ${t.message}" }
                }
            }
        }
    }

    /** Starts the GL preview on [s]; on failure switches to the plain preview (the viewfinder is rebuilt). */
    private suspend fun startRenderer(s: Surface, width: Int, height: Int, l: Lens): Surface? {
        val r = PreviewRenderer(getApplication())
        return try {
            val cameraSurface = r.start(s, width, height, l.previewSize.width, l.previewSize.height)
            renderer = r
            thumbSurfaces.forEach { (key, t) -> r.attachThumb(key, t.surface, t.width, t.height, t.look, t.radius) }
            viewModelScope.launch(Dispatchers.Default) { r.addThumbLooks(recipes.associate { it.name to thumbLut(it) }) }
            withContext(Dispatchers.Main) {
                refreshOverlays()
                applyPreviewStyle()
                rendererRunning = true
            }
            cameraSurface
        } catch (t: Throwable) {
            r.close()
            withContext(Dispatchers.Main) {
                glPreviewFailed = true
                message = "Live recipe preview unavailable (${t.message}); showing the plain camera preview."
            }
            null
        }
    }

    private fun releaseRenderer() {
        val r = renderer
        renderer = null
        r?.close()
        if (r != null) viewModelScope.launch(Dispatchers.Main) { rendererRunning = false }
    }

    private fun closeCamera() {
        controller.stop()
        releaseRenderer()
        opened = false
        openedWith = null
        cameraTarget = null
        openedLens = null
    }

    override fun onCleared() {
        // Close on the camera thread (it may be opening the device) and wait briefly for it.
        val ex = Executors.newSingleThreadExecutor()
        try {
            runCatching { ex.submit { kotlinx.coroutines.runBlocking(cameraThread) { closeCamera() } }.get(3, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            ex.shutdown()
        }
        controller.close()
        releaseRenderer()
        sound.release()
        cameraThread.close()
        lookThread.close()
    }

    companion object {
        /** The picture the still look thumbnails are made from (scripts/make_look_sample.py). */
        const val SAMPLE_ASSET = "look_sample.jpg"

        /** Frames merged for one low-light photo. */
        const val MERGE_FRAMES = 6
    }
}
