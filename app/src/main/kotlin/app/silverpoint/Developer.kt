package app.silverpoint

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Gainmap
import android.net.Uri
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import app.silverpoint.pipeline.CpuDeveloper
import app.silverpoint.pipeline.DevelopParams
import app.silverpoint.pipeline.GpuDeveloper
import app.silverpoint.pipeline.HdrGainMap
import app.silverpoint.pipeline.LookLut
import app.silverpoint.pipeline.RawMetadata
import app.silverpoint.recipes.Recipe
import app.silverpoint.storage.GallerySaver
import app.silverpoint.storage.PhotoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** One photo waiting to be developed: its RAW samples (copied out of the camera's buffer) and everything needed to finish it. */
class DevelopJob(
    /** 16-bit little-endian samples, [rowStride] bytes per row. */
    val raw: ByteBuffer,
    val rowStride: Int,
    val meta: RawMetadata,
    val info: PhotoInfo,
    val rotation: Int,
    val recipe: Recipe,
    /** How many frames were merged into [raw] (1 = single shot). */
    val frames: Int,
    /** Milliseconds spent merging, for the timing line. */
    val mergeMs: Long = 0,
    /** Save as Ultra HDR (JPEG with a gain map). */
    val ultraHdr: Boolean = false,
    /** The photo's shape, long side over short; 4:3 keeps the whole sensor (see [CameraMath.cropFor]). */
    val aspect: Float = CameraMath.SENSOR_RATIO,
)

/** What came out of a job. */
sealed interface DevelopOutcome {
    class Saved(val uri: Uri, val thumbnail: Bitmap, val summary: String) : DevelopOutcome
    class Failed(val message: String) : DevelopOutcome
}

/**
 * Develops photos one at a time on its own thread, independent of the screen: the camera is free
 * again as soon as a frame is copied in. Lives as long as the app process; while jobs are pending a
 * foreground service ([DevelopService]) keeps the process alive if the user leaves the app.
 */
class Developer(private val app: Application) {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "silverpoint-develop") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)
    private val prepareThread = Executors.newSingleThreadExecutor { r -> Thread(r, "silverpoint-merge") }.asCoroutineDispatcher()
    private val prepareScope = CoroutineScope(SupervisorJob() + prepareThread)
    private var gpu: GpuDeveloper? = null
    /** GPU failures so far; after [GPU_ATTEMPTS] the CPU develops the rest of the session. */
    private var gpuFailures = 0
    /** Why the GPU last failed, shown in the timing line so a device report says what went wrong. */
    private var gpuError: String? = null
    private val looks = ConcurrentHashMap<String, LookLut>()

    private val _pending = MutableStateFlow(0)

    /** Jobs submitted and not finished yet. */
    val pending: StateFlow<Int> = _pending

    private val _outcomes = MutableSharedFlow<DevelopOutcome>(extraBufferCapacity = 8)
    val outcomes: SharedFlow<DevelopOutcome> = _outcomes

    /**
     * The last photo saved, kept for a camera screen opened after it finished (the shutter pressed,
     * then Back at once): [outcomes] has no replay, so it would otherwise never see that photo.
     */
    private val _lastSaved = MutableStateFlow<DevelopOutcome.Saved?>(null)
    val lastSaved: StateFlow<DevelopOutcome.Saved?> = _lastSaved

    /** The final-render look table for a recipe, baked once (a few hundred milliseconds). */
    fun look(r: Recipe): LookLut = looks.getOrPut(r.name) { DevelopParams.bakeLook(r.params) }

    /**
     * A photo is on its way (call at the shutter press, while the app is in front): counts as
     * pending and starts the foreground service. Follow with [submit] or [abandon].
     */
    fun begin() {
        if (_pending.getAndUpdate { it + 1 } == 0) DevelopService.start(app)
    }

    /** The photo from [begin] will not come (capture failed). */
    fun abandon() {
        _pending.update { maxOf(0, it - 1) }
    }

    /**
     * Runs [block] (merging or copying captured frames, then [submit] or [abandon]) on the merge
     * thread. Lives as long as the app, not the camera screen, so a photo whose frames were
     * captured is finished even if the screen goes away meanwhile.
     */
    fun prepare(block: suspend () -> Unit) {
        prepareScope.launch { block() }
    }

    /** Develops a photo announced with [begin]. The service stops itself once nothing is pending. */
    fun submit(job: DevelopJob) {
        scope.launch {
            val outcome = runCatching { develop(job) }.getOrElse { DevelopOutcome.Failed("Could not save the photo: ${it.message}") }
            if (outcome is DevelopOutcome.Saved) _lastSaved.value = outcome
            _outcomes.emit(outcome)
            _pending.update { maxOf(0, it - 1) }
        }
    }

    private fun develop(job: DevelopJob): DevelopOutcome {
        val start = SystemClock.elapsedRealtime()
        val takenAt = job.info.takenAtMillis
        val params = DevelopParams.build(
            job.meta, job.recipe.params, job.rotation,
            lut = look(job.recipe),
            grainSeed = (takenAt xor (takenAt ushr 32)).toInt(),
        )
        var how = "GPU"
        val developed = developGpu(job.raw, job.rowStride, params, job.ultraHdr) ?: run {
            how = "CPU (GPU unavailable: ${gpuError ?: "unknown"})"
            developCpu(job.raw, job.rowStride, params)
        }
        // The chosen aspect ratio: keep the middle of the photo (the DNG keeps the whole sensor).
        val crop = CameraMath.cropFor(developed.bitmap.width, developed.bitmap.height, job.aspect)
        val full = developed.bitmap
        val fullW = full.width.toFloat()
        val fullH = full.height.toFloat()
        val cropped = crop[2] != full.width || crop[3] != full.height
        val bitmap = if (cropped) Bitmap.createBitmap(full, crop[0], crop[1], crop[2], crop[3]).also { full.recycle() } else full
        var hdr = ""
        if (job.ultraHdr) {
            hdr = developed.lowLinear?.let { low ->
                runCatching {
                    val map = HdrGainMap.encode(low, developed.lowWidth, developed.lowHeight, params.knee, params.rotation)
                    attachGainMap(bitmap, if (cropped) map.crop(crop[0] / fullW, crop[1] / fullH, crop[2] / fullW, crop[3] / fullH) else map)
                }
                    .fold({ ", Ultra HDR" }, { ", no Ultra HDR (${it.message})" })
            } ?: ", no Ultra HDR (gain map unavailable)"
        }
        val developedMs = SystemClock.elapsedRealtime() - start
        val uri = GallerySaver.saveJpeg(app, bitmap, job.info)
        val totalMs = SystemClock.elapsedRealtime() - start
        val thumb = bitmap.scale(THUMB_WIDTH, THUMB_WIDTH * bitmap.height / bitmap.width)
        bitmap.recycle()
        val merged = if (job.frames > 1) "${job.frames} frames merged in ${job.mergeMs} ms, " else ""
        return DevelopOutcome.Saved(uri, thumb, "$merged$how: developed in $developedMs ms, saved in $totalMs ms$hdr")
    }

    /** Attaches [map] as the bitmap's Ultra HDR gain map; `Bitmap.compress` then writes it into the JPEG. */
    private fun attachGainMap(bitmap: Bitmap, map: HdrGainMap.Map) {
        val contents = createBitmap(map.width, map.height, Bitmap.Config.ALPHA_8)
        val row = contents.rowBytes
        val buf = ByteBuffer.allocateDirect(row * map.height)
        for (y in 0 until map.height) {
            buf.position(y * row)
            buf.put(map.bytes, y * map.width, map.width)
        }
        buf.position(0)
        contents.copyPixelsFromBuffer(buf)
        val h = HdrGainMap.HEADROOM
        val gm = Gainmap(contents).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(h, h, h)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
            setMinDisplayRatioForHdrTransition(1f)
            setDisplayRatioForFullHdr(h)
        }
        bitmap.gainmap = gm
    }

    /** A developed photo and, when asked for, its quarter-resolution linear luminance. */
    private class Developed(val bitmap: Bitmap, val lowLinear: FloatArray?, val lowWidth: Int, val lowHeight: Int)

    private fun developGpu(buffer: ByteBuffer, rowStride: Int, params: DevelopParams, readLowRes: Boolean): Developed? {
        if (gpuFailures >= GPU_ATTEMPTS) return null
        return try {
            val g = gpu ?: GpuDeveloper(app).also { gpu = it }
            val r = g.develop(buffer, rowStride, params, readLowRes)
            Developed(r.bitmap, r.lowLinear, r.lowWidth, r.lowHeight)
        } catch (t: Throwable) {
            // Start from a fresh GL context next time: one failure (for example running out of
            // memory) should not leave every later photo on the much slower CPU path.
            gpuFailures++
            gpuError = "${t.javaClass.simpleName}: ${t.message}"
            runCatching { gpu?.close() }
            gpu = null
            null
        }
    }

    private fun developCpu(buffer: ByteBuffer, rowStride: Int, params: DevelopParams): Developed {
        val stride = rowStride / 2
        val raw = ShortArray(stride * params.height)
        buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(raw, 0, minOf(raw.size, buffer.capacity() / 2))
        val out = CpuDeveloper.develop(raw, stride, params)
        val low = out.lowRes
        return Developed(Bitmap.createBitmap(out.argb, out.width, out.height, Bitmap.Config.ARGB_8888), low?.linear, low?.width ?: 0, low?.height ?: 0)
    }

    companion object {
        const val THUMB_WIDTH = 192

        /** GPU failures before the rest of the session develops on the CPU. */
        const val GPU_ATTEMPTS = 2

        /** Most photos waiting at once (each holds about 25 MB). */
        const val MAX_PENDING = 3
    }
}
