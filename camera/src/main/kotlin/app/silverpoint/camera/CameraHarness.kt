package app.silverpoint.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A probe step failed. [stage] says where (open, session, converge, capture, image, dng). */
class ProbeException(val stage: String, message: String) : Exception(message)

/**
 * Blocking wrappers around the callback-based Camera2 API, for probe tests that run on a worker thread.
 *
 * Everything opened through the harness is closed by [close], in reverse order.
 */
internal class CameraHarness(private val manager: CameraManager) : AutoCloseable {
    private val thread = HandlerThread("silverpoint-probe-v2").also { it.start() }
    val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val closeables = ArrayDeque<AutoCloseable>()

    fun open(cameraId: String, timeoutMs: Long = 5_000): CameraDevice {
        val opened = CountDownLatch(1)
        val ref = AtomicReference<CameraDevice?>()
        val error = AtomicReference<String?>()
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    ref.set(camera)
                    opened.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    error.compareAndSet(null, "disconnected")
                    camera.close()
                    opened.countDown()
                }

                override fun onError(camera: CameraDevice, code: Int) {
                    error.compareAndSet(null, "open error $code")
                    camera.close()
                    opened.countDown()
                }
            }, handler)
        } catch (e: SecurityException) {
            throw ProbeException("open", "camera permission missing: ${e.message}")
        }
        if (!opened.await(timeoutMs, TimeUnit.MILLISECONDS)) throw ProbeException("open", "timeout opening camera $cameraId")
        val device = ref.get() ?: throw ProbeException("open", error.get() ?: "camera $cameraId did not open")
        closeables.addLast(AutoCloseable { device.close() })
        return device
    }

    fun session(device: CameraDevice, outputs: List<OutputConfiguration>, timeoutMs: Long = 5_000): CameraCaptureSession {
        val configured = CountDownLatch(1)
        val ref = AtomicReference<CameraCaptureSession?>()
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    ref.set(s)
                    configured.countDown()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    configured.countDown()
                }
            },
        )
        device.createCaptureSession(config)
        if (!configured.await(timeoutMs, TimeUnit.MILLISECONDS)) throw ProbeException("session", "timeout configuring session")
        val session = ref.get() ?: throw ProbeException("session", "session configuration failed")
        closeables.addLast(AutoCloseable {
            runCatching { session.stopRepeating() }
            session.close()
        })
        return session
    }

    /** A YUV output that drops every frame immediately and just counts them, standing in for a viewfinder. */
    fun previewSink(width: Int, height: Int): PreviewSink {
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        val sink = PreviewSink(reader)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close()
            sink.frames.incrementAndGet()
        }, handler)
        closeables.addLast(reader)
        return sink
    }

    /** A RAW output that keeps every frame open until [RawCollector.closeAll], like a merge buffer would. */
    fun rawCollector(width: Int, height: Int, maxImages: Int): RawCollector {
        val reader = ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, maxImages)
        val collector = RawCollector(reader)
        reader.setOnImageAvailableListener({ r -> collector.onAvailable(r) }, handler)
        closeables.addLast(AutoCloseable {
            collector.closeAll()
            reader.close()
        })
        return collector
    }

    /** Starts a repeating request and waits until AE and AWB settle (or [timeoutMs] passes). */
    fun repeatUntilConverged(session: CameraCaptureSession, request: CaptureRequest, timeoutMs: Long = 3_000): Convergence {
        val start = SystemClock.elapsedRealtime()
        val done = CountDownLatch(1)
        val last = AtomicReference<TotalCaptureResult?>()
        val frames = AtomicInteger()
        val firstFrameAt = AtomicReference<Long?>()
        session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                last.set(result)
                if (frames.incrementAndGet() == 1) firstFrameAt.set(SystemClock.elapsedRealtime())
                if (isSettled(result)) done.countDown()
            }
        }, handler)
        val settled = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        val result = last.get()
        return Convergence(
            settled = settled,
            millis = SystemClock.elapsedRealtime() - start,
            firstFrameMillis = firstFrameAt.get()?.let { it - start },
            frames = frames.get(),
            aeState = result?.get(CaptureResult.CONTROL_AE_STATE),
            awbState = result?.get(CaptureResult.CONTROL_AWB_STATE),
            result = result,
        )
    }

    fun capture(session: CameraCaptureSession, request: CaptureRequest, timeoutMs: Long = 5_000): TotalCaptureResult =
        burst(session, listOf(request), timeoutMs).single()

    fun burst(session: CameraCaptureSession, requests: List<CaptureRequest>, timeoutMs: Long): List<TotalCaptureResult> {
        val latch = CountDownLatch(requests.size)
        val results = java.util.Collections.synchronizedList(mutableListOf<TotalCaptureResult>())
        val failure = AtomicReference<String?>()
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                results.add(result)
                latch.countDown()
            }

            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                failure.compareAndSet(null, "capture failed, reason ${f.reason}")
                latch.countDown()
            }
        }
        if (requests.size == 1) session.capture(requests[0], callback, handler) else session.captureBurst(requests, callback, handler)
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw ProbeException("capture", "timeout: ${results.size} of ${requests.size} results arrived")
        }
        failure.get()?.let { throw ProbeException("capture", it) }
        return results.sortedBy { it.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L }
    }

    override fun close() {
        while (closeables.isNotEmpty()) runCatching { closeables.removeLast().close() }
        thread.quitSafely()
        // Give the camera HAL a moment to release before the next test opens it.
        runCatching { Thread.sleep(300) }
    }

    private fun isSettled(r: TotalCaptureResult): Boolean {
        val ae = r.get(CaptureResult.CONTROL_AE_STATE)
        val awb = r.get(CaptureResult.CONTROL_AWB_STATE)
        val aeOk = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
        val awbOk = awb == null || awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
            awb == CaptureResult.CONTROL_AWB_STATE_LOCKED
        return aeOk && awbOk
    }
}

internal class PreviewSink(val reader: ImageReader) {
    val frames = AtomicInteger()
    val surface get() = reader.surface
}

internal data class Convergence(
    val settled: Boolean,
    val millis: Long,
    val firstFrameMillis: Long?,
    val frames: Int,
    val aeState: Int?,
    val awbState: Int?,
    val result: TotalCaptureResult?,
)

/** Holds RAW images open until told to release them. */
internal class RawCollector(val reader: ImageReader) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val images = mutableListOf<Image>()
    var overflow: String? = null
        private set

    val surface get() = reader.surface

    fun onAvailable(r: ImageReader) {
        try {
            val image = r.acquireNextImage() ?: return
            lock.withLock {
                images += image
                changed.signalAll()
            }
        } catch (t: Throwable) {
            // Thrown when more than maxImages are held: exactly the limit the burst test looks for.
            lock.withLock {
                if (overflow == null) overflow = "${t.javaClass.simpleName}: ${t.message}"
                changed.signalAll()
            }
        }
    }

    /** Waits until [n] images are held. Returns how many arrived. */
    fun await(n: Int, timeoutMs: Long): Int = lock.withLock {
        var leftNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (images.size < n && overflow == null && leftNs > 0) leftNs = changed.awaitNanos(leftNs)
        images.size
    }

    fun held(): List<Image> = lock.withLock { images.toList() }

    fun closeAll() = lock.withLock {
        images.forEach { runCatching { it.close() } }
        images.clear()
        overflow = null
    }
}
