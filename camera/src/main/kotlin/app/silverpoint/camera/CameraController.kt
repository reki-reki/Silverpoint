package app.silverpoint.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A lens the app can shoot with: a physical camera inside a logical camera, or a plain camera. */
class Lens(
    val logicalId: String,
    /** Null when [logicalId] is a single camera rather than a logical multi-camera. */
    val physicalId: String?,
    val logical: CameraCharacteristics,
    /** The lens's own characteristics (colour matrices, CFA, ranges). */
    val characteristics: CameraCharacteristics,
    val rawSize: Size,
    val previewSize: Size,
    /** Kind and zoom relative to the main lens, when known. */
    val label: LensLabel? = null,
) {
    /** Stable identity for settings: the physical ID, or the camera ID. */
    val id: String get() = physicalId ?: logicalId
    val sensorOrientation: Int get() = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val aeCompensationRange: Range<Int> get() = logical.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    val aeCompensationStep: Rational get() = logical.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 3)
    val focalLengthMm: Float? get() = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
    val aperture: Float? get() = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
    val isoRange: IntRange get() = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper } ?: 100..1600
    val exposureRange: LongRange get() = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper } ?: 100_000L..100_000_000L
    /** Closest focus in diopters; 0 means fixed focus. */
    val minFocusDiopters: Float get() = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val activeArray: Rect get() = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, rawSize.width, rawSize.height)
    val logicalActiveArray: Rect get() = logical.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: activeArray
    val hasAutofocus: Boolean get() = minFocusDiopters > 0f
    /** The lens can fire the phone's flash (the logical camera owns it on multi-camera phones). */
    val hasFlash: Boolean get() = logical.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true || characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    /** Button text: "0.6", "1", "3", "5" (tele zooms are rounded the way phone makers label them). */
    val buttonText: String
        get() = label?.let { if (it.zoom >= 2) Math.round(it.zoom).toString() else it.zoomText } ?: "?"

    companion object {
        private fun backRawCameras(manager: CameraManager) = manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }.filter { (_, ch) ->
            ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        }

        private fun build(logicalId: String, logical: CameraCharacteristics, physicalId: String?, lens: CameraCharacteristics, label: LensLabel?): Lens? {
            val raw = CharacteristicsDump.rawSizes(lens, maxResolution = false).firstOrNull() ?: return null
            val yuv = lens.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)?.map { it.width to it.height } ?: emptyList()
            val preview = PreviewSizes.pick(yuv) ?: return null
            return Lens(logicalId, physicalId, logical, lens, raw, Size(preview.first, preview.second), label)
        }

        private fun equivalentMm(c: CameraCharacteristics): Double? {
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
            val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            return LensLabel.equivalentMm(f.toDouble(), s.width.toDouble(), s.height.toDouble())
        }

        /**
         * Every rear lens that delivers RAW, widest first. Inside a logical multi-camera these are its
         * physical cameras; the main lens is the one with the largest sensor.
         */
        fun all(manager: CameraManager): List<Lens> {
            val back = backRawCameras(manager)
            require(back.isNotEmpty()) { "no back camera with RAW support" }
            val (logicalId, logical) = back.firstOrNull { (_, ch) -> ch.physicalCameraIds.isNotEmpty() } ?: back.first()
            if (logical.physicalCameraIds.isEmpty()) {
                return listOfNotNull(build(logicalId, logical, null, logical, null))
            }
            val physical = logical.physicalCameraIds.mapNotNull { id ->
                CharacteristicsDump.safe { id to manager.getCameraCharacteristics(id) }
            }
            val main = physical.maxByOrNull { (_, c) -> c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { it.width * it.height } ?: 0f }
            val mainEq = main?.let { equivalentMm(it.second) }
            return physical.mapNotNull { (id, c) ->
                val label = mainEq?.let { m ->
                    val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    if (f != null && s != null) LensLabel.of(f.toDouble(), s.width.toDouble(), s.height.toDouble(), m) else null
                }
                build(logicalId, logical, id, c, label)
            }.sortedBy { it.label?.zoom ?: 1.0 }
        }
    }
}

/** One RAW frame with the metadata needed to develop it. Close it to give the buffer back to the camera. */
class RawCapture(
    val image: Image,
    val result: TotalCaptureResult,
    /** The physical lens's own result when shooting through a logical camera, else [result]. */
    val lensResult: CaptureResult,
    val lens: Lens,
    private val onRelease: () -> Unit = {},
) : Closeable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            image.close()
            onRelease()
        }
    }
}

/**
 * The RAW reader plus how many of its images are still out with callers. Closing an ImageReader
 * invalidates every image it handed out, so a reader whose session ended waits for the last
 * outstanding capture (a merge or a copy still reading it) before it is closed.
 */
private class RawReader(val reader: ImageReader) {
    private val outstanding = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var retired = false

    fun acquired() {
        outstanding.incrementAndGet()
    }

    fun released() {
        if (outstanding.decrementAndGet() == 0 && retired) runCatching { reader.close() }
    }

    /** No more images will be taken from it; close now or when the last capture is closed. */
    fun retire() {
        reader.setOnImageAvailableListener(null, null)
        retired = true
        if (outstanding.get() == 0) runCatching { reader.close() }
    }
}

/** How the lens focuses. Points are in the upright viewfinder, 0..1 from the top-left. */
sealed interface Focus {
    data object Continuous : Focus
    data class Point(val u: Float, val v: Float) : Focus
    data class Manual(val diopters: Float) : Focus
}

/** The flash for stills: never lit for the preview. */
enum class Flash { Off, Auto, On }

/**
 * Settings that apply to the preview and to stills. [iso] and [exposureNs] both set mean full
 * manual exposure; one of them alone is a priority mode, emulated at capture time (see
 * [ExposureMath.forCapture]) while the preview keeps auto-exposure.
 */
data class Controls(
    val aeCompensation: Int = 0,
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val focus: Focus = Focus.Continuous,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    /** Metering point in the upright viewfinder; null meters the whole frame. */
    val meteringPoint: Pair<Float, Float>? = null,
    val flash: Flash = Flash.Off,
)

/** What the lens is doing right now, from the preview's capture results. */
data class PreviewState(
    val iso: Int?,
    val exposureNs: Long?,
    val focusDiopters: Float?,
    val afState: Int?,
    val aeState: Int?,
)

/** Exposure for a still, overriding auto-exposure. */
data class StillExposure(val iso: Int, val exposureNs: Long)

/**
 * Runs the camera for the viewfinder and RAW stills: one session with a preview surface and a RAW
 * reader, both bound to the lens's physical camera. Methods block (except [setControls] and
 * [focusAt]); call them from a background thread.
 */
class CameraController(private val manager: CameraManager) : Closeable {
    private val thread = HandlerThread("silverpoint-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    @Volatile private var device: CameraDevice? = null
    @Volatile private var deviceId: String? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var rawReader: RawReader? = null
    @Volatile private var lens: Lens? = null
    @Volatile private var previewSurface: Surface? = null
    private val rawFrames = LinkedBlockingQueue<Image>()

    @Volatile private var controls = Controls()
    @Volatile private var lastStateAt = 0L

    /** Latest preview state; updated about ten times a second. */
    @Volatile var previewState: PreviewState? = null
        private set

    /** Called on the camera thread with each new [previewState]. */
    @Volatile var onPreviewState: ((PreviewState) -> Unit)? = null

    /** Called on the camera thread if the camera is lost (another app took it, or an error). */
    var onError: ((String) -> Unit)? = null

    /**
     * Opens [lens] and starts the preview into [preview]. Size the surface to [Lens.previewSize]
     * first. Switching to another lens of the same camera keeps the device open and only rebuilds
     * the session.
     */
    fun open(lens: Lens, preview: Surface, timeoutMs: Long = 5_000) {
        val reuse = device != null && deviceId == lens.logicalId
        closeSession()
        if (!reuse) closeDevice()
        this.lens = lens
        previewSurface = preview
        previewState = null

        val camera = if (reuse) device!! else openDevice(lens.logicalId, timeoutMs)

        val reader = ImageReader.newInstance(lens.rawSize.width, lens.rawSize.height, ImageFormat.RAW_SENSOR, MAX_RAW_IMAGES)
        reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { rawFrames.put(it) } }, handler)
        rawReader = RawReader(reader)

        val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(reader.surface)).onEach { o ->
            lens.physicalId?.let { o.setPhysicalCameraId(it) }
        }
        val configured = CountDownLatch(1)
        camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    configured.countDown()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    configured.countDown()
                }
            }))
        check(configured.await(timeoutMs, TimeUnit.MILLISECONDS)) { "timed out configuring the camera" }
        checkNotNull(session) { "camera session could not be configured" }
        startPreview()
    }

    private fun openDevice(id: String, timeoutMs: Long): CameraDevice {
        val opened = CountDownLatch(1)
        val error = AtomicReference<String?>()
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    deviceId = id
                    opened.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device == camera) device = null
                    if (opened.count > 0) error.set("camera disconnected") else onError?.invoke("Camera disconnected")
                    opened.countDown()
                }

                override fun onError(camera: CameraDevice, code: Int) {
                    camera.close()
                    if (device == camera) device = null
                    if (opened.count > 0) error.set("camera error $code") else onError?.invoke("Camera error $code")
                    opened.countDown()
                }
            }, handler)
        } catch (e: SecurityException) {
            throw IllegalStateException("camera permission missing", e)
        }
        check(opened.await(timeoutMs, TimeUnit.MILLISECONDS)) { "timed out opening the camera" }
        return device ?: throw IllegalStateException(error.get() ?: "camera did not open")
    }

    /** New preview and still settings; applied to the preview at once. */
    fun setControls(c: Controls) {
        controls = c
        handler.post { runCatching { startPreview() }.onFailure { Log.w(TAG, "could not apply camera controls", it) } }
    }

    /** Focuses (and meters, unless [meter] is false) at a viewfinder point, then holds focus there. */
    fun focusAt(u: Float, v: Float, meter: Boolean = true) {
        controls = controls.copy(focus = Focus.Point(u, v), meteringPoint = if (meter) u to v else controls.meteringPoint)
        handler.post {
            runCatching {
                val camera = device ?: return@runCatching
                val s = session ?: return@runCatching
                val l = lens ?: return@runCatching
                val surface = previewSurface ?: return@runCatching
                // Cancel any running scan, then start one with the new regions.
                val trigger = request(camera, CameraDevice.TEMPLATE_PREVIEW, l, controls).apply {
                    addTarget(surface)
                    PhysicalKeys.set(this, CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, l.physicalId, l.logical)
                }
                s.capture(trigger.build(), null, handler)
                startPreview()
                trigger.apply {
                    PhysicalKeys.set(this, CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START, l.physicalId, l.logical)
                }
                s.capture(trigger.build(), null, handler)
            }.onFailure { Log.w(TAG, "tap to focus failed", it) }
        }
    }

    /**
     * Takes one RAW still with the current settings, or with [exposure] if given. The returned
     * capture must be closed once developed.
     */
    fun capture(exposure: StillExposure? = null, fireFlash: Boolean = false): RawCapture = captureBurst(1, exposure, fireFlash).single()

    /**
     * Before a flash photo: runs auto-exposure's precapture sequence (the pre-flash that meters the
     * scene with the flash) and says whether the flash will fire: always with [Flash.On], when
     * auto-exposure asks for it with [Flash.Auto]. Blocks for up to [timeoutMs].
     */
    fun prepareFlash(timeoutMs: Long = 2_000): Boolean {
        val c = controls
        if (c.flash == Flash.Off) return false
        val camera = device ?: return false
        val s = session ?: return false
        val l = lens ?: return false
        val surface = previewSurface ?: return false
        val before = previewState?.aeState
        val trigger = request(camera, CameraDevice.TEMPLATE_PREVIEW, l, c).apply {
            addTarget(surface)
            PhysicalKeys.set(this, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START, l.physicalId, l.logical)
        }.build()
        val started = SystemClock.elapsedRealtime()
        s.capture(trigger, null, handler)
        // Wait for the sequence to run (PRECAPTURE) and settle; give up quietly after the timeout.
        var seenPrecapture = false
        var after: Int? = null
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            Thread.sleep(40)
            val st = previewState?.aeState ?: continue
            if (st == CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) seenPrecapture = true
            val settled = st == CameraMetadata.CONTROL_AE_STATE_CONVERGED || st == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED || st == CameraMetadata.CONTROL_AE_STATE_LOCKED
            if (settled && (seenPrecapture || SystemClock.elapsedRealtime() - started > 400)) {
                after = st
                break
            }
        }
        return c.flash == Flash.On || before == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED || after == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED
    }

    /**
     * Takes [count] RAW frames back to back (at most [MAX_BURST]) with the same settings. Frames
     * arrive at the sensor's frame rate; all are returned at once and must all be closed.
     */
    fun captureBurst(count: Int, exposure: StillExposure? = null, fireFlash: Boolean = false): List<RawCapture> {
        // Long manual exposures need a longer wait than the usual few seconds.
        val frameMs = (exposure?.exposureNs ?: previewState?.exposureNs ?: 0L) / 1_000_000L
        val timeoutMs = 4_000L + count * (frameMs * 2 + 200)
        require(count in 1..MAX_BURST) { "burst of $count frames is not supported" }
        val camera = checkNotNull(device) { "camera not open" }
        val s = checkNotNull(session) { "camera not open" }
        val l = checkNotNull(lens)
        val holder = checkNotNull(rawReader)
        val reader = holder.reader
        drainRawFrames()

        val request = request(camera, CameraDevice.TEMPLATE_STILL_CAPTURE, l, controls).apply {
            addTarget(reader.surface)
            // Sent to the lens too: this phone ignores logical-only settings on a physical lens.
            PhysicalKeys.set(this, CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF, l.physicalId, l.logical)
            PhysicalKeys.set(this, CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF, l.physicalId, l.logical)
            PhysicalKeys.set(this, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON, l.physicalId, l.logical)
            exposure?.let { PhysicalKeys.manualExposure(this, l.logical, l.physicalId, it.iso, it.exposureNs) }
            // With auto-exposure off the flash has to be fired by hand.
            if (fireFlash && exposure != null) PhysicalKeys.set(this, CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE, l.physicalId, l.logical)
        }.build()

        val results = LinkedBlockingQueue<TotalCaptureResult>()
        val failure = AtomicReference<String?>()
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, total: TotalCaptureResult) {
                results.put(total)
            }

            override fun onCaptureFailed(cs: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                failure.set("capture failed (reason ${f.reason})")
            }
        }
        if (count == 1) s.capture(request, callback, handler) else s.captureBurst(List(count) { request }, callback, handler)

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val totals = ArrayList<TotalCaptureResult>()
        while (totals.size < count) {
            failure.get()?.let { msg -> totals.clear(); throw IllegalStateException(msg) }
            val left = deadline - SystemClock.elapsedRealtime()
            check(left > 0) { "timed out waiting for the photo" }
            results.poll(minOf(left, 100), TimeUnit.MILLISECONDS)?.let { totals += it }
        }

        // Collect the burst's images. Only stills target the RAW reader and it was drained above, so
        // these are this burst's frames.
        val images = ArrayList<Image>()
        val out = ArrayList<RawCapture>()
        try {
            while (images.size < count) {
                val left = deadline - SystemClock.elapsedRealtime()
                check(left > 0) { "timed out waiting for the RAW image" }
                rawFrames.poll(left, TimeUnit.MILLISECONDS)?.let { images += it }
            }
            // Pair images with results by sensor timestamp (the lens's own, else the logical one),
            // falling back to order: both arrive in capture order.
            fun stamp(t: TotalCaptureResult): Long? =
                l.physicalId?.let { t.physicalCameraTotalResults[it]?.get(CaptureResult.SENSOR_TIMESTAMP) } ?: t.get(CaptureResult.SENSOR_TIMESTAMP)
            val sortedImages = images.sortedBy { it.timestamp }
            val sortedTotals = totals.sortedBy { stamp(it) ?: 0L }
            val byStamp = totals.associateBy { stamp(it) }
            for ((k, image) in sortedImages.withIndex()) {
                val total = byStamp[image.timestamp] ?: sortedTotals[k]
                val lensResult = l.physicalId?.let { total.physicalCameraTotalResults[it] } ?: total
                holder.acquired()
                out += RawCapture(image, total, lensResult, l, holder::released)
            }
        } catch (t: Throwable) {
            out.forEach { it.close() }
            images.filter { img -> out.none { it.image === img } }.forEach { it.close() }
            throw t
        }
        return out.sortedBy { it.image.timestamp }
    }

    /** Releases the camera but keeps the controller usable for a later [open]. */
    fun stop() {
        closeSession()
        closeDevice()
    }

    override fun close() {
        stop()
        thread.quitSafely()
    }

    private fun closeSession() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        rawReader?.retire()
        drainRawFrames()
        session = null
        rawReader = null
    }

    private fun closeDevice() {
        runCatching { device?.close() }
        device = null
        deviceId = null
    }

    /** Closes any RAW images nobody claimed, so the reader's buffers are free. */
    private fun drainRawFrames() {
        while (true) rawFrames.poll()?.close() ?: break
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, total: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastStateAt < 100) return
            lastStateAt = now
            val l = lens ?: return
            val res: CaptureResult = l.physicalId?.let { total.physicalCameraTotalResults[it] } ?: total
            val state = PreviewState(
                iso = res.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = res.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                focusDiopters = res.get(CaptureResult.LENS_FOCUS_DISTANCE),
                afState = res.get(CaptureResult.CONTROL_AF_STATE) ?: total.get(CaptureResult.CONTROL_AF_STATE),
                aeState = res.get(CaptureResult.CONTROL_AE_STATE) ?: total.get(CaptureResult.CONTROL_AE_STATE),
            )
            previewState = state
            onPreviewState?.invoke(state)
        }
    }

    private fun startPreview() {
        val camera = device ?: return
        val s = session ?: return
        val l = lens ?: return
        val surface = previewSurface ?: return
        val c = controls
        val req = request(camera, CameraDevice.TEMPLATE_PREVIEW, l, c).apply {
            addTarget(surface)
            // Full manual exposure shows on the preview; priority modes preview with auto-exposure.
            if (c.iso != null && c.exposureNs != null) {
                val iso = c.iso.coerceIn(l.isoRange)
                val ns = c.exposureNs.coerceIn(l.exposureRange)
                // Keep the preview fluid: exposure and frame duration are limited to 1/15 s even for
                // longer stills, and ISO makes up the difference (as far as the sensor goes) so the
                // preview stays as bright as the photo.
                val previewNs = minOf(ns, PREVIEW_MAX_EXPOSURE_NS).coerceIn(l.exposureRange)
                val previewIso = Math.round(iso.toDouble() * ns / previewNs).toInt().coerceIn(l.isoRange)
                PhysicalKeys.manualExposure(this, l.logical, l.physicalId, previewIso, previewNs)
            }
        }.build()
        s.setRepeatingRequest(req, previewCallback, handler)
    }

    /** Request with the settings shared by preview and stills: 3A, focus, regions, OIS, compensation. */
    private fun request(camera: CameraDevice, template: Int, l: Lens, c: Controls): CaptureRequest.Builder {
        val b = if (l.physicalId == null) camera.createCaptureRequest(template) else camera.createCaptureRequest(template, setOf(l.physicalId))
        PhysicalKeys.set(b, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO, l.physicalId, l.logical)
        val aeMode = when {
            !l.hasFlash -> CameraMetadata.CONTROL_AE_MODE_ON
            c.flash == Flash.Auto -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            c.flash == Flash.On -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            else -> CameraMetadata.CONTROL_AE_MODE_ON
        }
        PhysicalKeys.set(b, CaptureRequest.CONTROL_AE_MODE, aeMode, l.physicalId, l.logical)
        PhysicalKeys.set(b, CaptureRequest.CONTROL_AWB_MODE, c.awbMode, l.physicalId, l.logical)
        PhysicalKeys.set(b, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.aeCompensation, l.physicalId, l.logical)
        if (l.hasAutofocus) {
            when (val f = c.focus) {
                Focus.Continuous ->
                    PhysicalKeys.set(b, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE, l.physicalId, l.logical)
                is Focus.Point -> {
                    PhysicalKeys.set(b, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO, l.physicalId, l.logical)
                    PhysicalKeys.setRegions(b, CaptureRequest.CONTROL_AF_REGIONS, f.u, f.v, l)
                }
                is Focus.Manual -> {
                    PhysicalKeys.set(b, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF, l.physicalId, l.logical)
                    PhysicalKeys.set(b, CaptureRequest.LENS_FOCUS_DISTANCE, f.diopters.coerceIn(0f, l.minFocusDiopters), l.physicalId, l.logical)
                }
            }
        }
        c.meteringPoint?.let { (u, v) -> PhysicalKeys.setRegions(b, CaptureRequest.CONTROL_AE_REGIONS, u, v, l) }
        PhysicalKeys.requestOis(b, l.logical, l.physicalId, l.characteristics)
        return b
    }

    companion object {
        /** Longest burst; the RAW reader holds this many frames (about 25 MB each) plus two. */
        const val MAX_BURST = 8
        const val MAX_RAW_IMAGES = MAX_BURST + 2
        const val PREVIEW_MAX_EXPOSURE_NS = 66_666_666L
        private const val TAG = "SilverpointCamera"
    }
}
