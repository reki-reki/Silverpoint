package app.silverpoint.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tries to take one RAW_SENSOR still for a [RawTarget] and reports how far it got.
 *
 * Blocking: call from a background thread. Never throws; every failure becomes a [CaptureOutcome].
 */
class RawCaptureTest(private val manager: CameraManager) {

    fun run(target: RawTarget, timeoutMs: Long = 20_000): CaptureOutcome {
        val start = SystemClock.elapsedRealtime()
        var stage = "open"
        val thread = HandlerThread("silverpoint-probe").also { it.start() }
        val handler = Handler(thread.looper)
        val executor = Executor { handler.post(it) }

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        try {
            // --- open the camera ---
            val opened = CountDownLatch(1)
            val deviceRef = AtomicReference<CameraDevice?>()
            val openError = AtomicReference<String?>()
            try {
                manager.openCamera(target.cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        deviceRef.set(camera)
                        opened.countDown()
                    }
    
                    override fun onDisconnected(camera: CameraDevice) {
                        openError.compareAndSet(null, "disconnected")
                        camera.close()
                        opened.countDown()
                    }
    
                    override fun onError(camera: CameraDevice, error: Int) {
                        openError.compareAndSet(null, "open error ${cameraError(error)}")
                        camera.close()
                        opened.countDown()
                    }
                }, handler)
            } catch (e: SecurityException) {
                return fail(target, stage, "camera permission missing: ${e.message}", start)
            }
            if (!opened.await(timeoutMs, TimeUnit.MILLISECONDS)) return fail(target, stage, "timeout opening camera", start)
            device = deviceRef.get() ?: return fail(target, stage, openError.get() ?: "camera did not open", start)

            // --- create a session with one RAW output ---
            stage = "session"
            val imageLatch = CountDownLatch(1)
            val imageInfo = AtomicReference<Map<String, Any?>>()
            reader = ImageReader.newInstance(target.width, target.height, ImageFormat.RAW_SENSOR, 1)
            reader.setOnImageAvailableListener({ r ->
                var image: Image? = null
                try {
                    image = r.acquireNextImage()
                    if (image != null) imageInfo.set(ImageStats.describe(image))
                } catch (t: Throwable) {
                    imageInfo.set(mapOf("imageError" to "${t.javaClass.simpleName}: ${t.message}"))
                } finally {
                    image?.close()
                    imageLatch.countDown()
                }
            }, handler)

            val output = OutputConfiguration(reader.surface)
            if (target.physicalId != null) output.setPhysicalCameraId(target.physicalId)
            if (target.maxResolution) output.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)

            val configured = CountDownLatch(1)
            val sessionRef = AtomicReference<CameraCaptureSession?>()
            val sessionError = AtomicReference<String?>()
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(output),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        sessionRef.set(s)
                        configured.countDown()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        sessionError.set("session configuration failed")
                        configured.countDown()
                    }
                },
            )
            device.createCaptureSession(sessionConfig)
            if (!configured.await(timeoutMs, TimeUnit.MILLISECONDS)) return fail(target, stage, "timeout configuring session", start)
            session = sessionRef.get() ?: return fail(target, stage, sessionError.get() ?: "no session", start)

            // --- capture ---
            stage = "capture"
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                if (target.maxResolution) {
                    set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                }
            }.build()

            val resultLatch = CountDownLatch(1)
            val resultInfo = AtomicReference<Map<String, Any?>>()
            val captureError = AtomicReference<String?>()
            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    resultInfo.set(describe(result))
                    resultLatch.countDown()
                }

                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    captureError.set("capture failed, reason ${failure.reason}")
                    resultLatch.countDown()
                }
            }, handler)

            if (!resultLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) return fail(target, stage, "timeout waiting for capture result", start)
            captureError.get()?.let { return fail(target, stage, it, start) }
            stage = "image"
            if (!imageLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) return fail(target, stage, "capture finished but no image arrived", start)

            val details = linkedMapOf<String, Any?>()
            details.putAll(imageInfo.get() ?: emptyMap())
            details["result"] = resultInfo.get()
            return CaptureOutcome(target, true, "image", null, SystemClock.elapsedRealtime() - start, details)
        } catch (t: Throwable) {
            return fail(target, stage, "${t.javaClass.simpleName}: ${t.message}", start)
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            thread.quitSafely()
            // Give the camera HAL a moment to release before the next test opens it.
            runCatching { Thread.sleep(300) }
        }
    }

    private fun fail(target: RawTarget, stage: String, error: String, start: Long) =
        CaptureOutcome(target, false, stage, error, SystemClock.elapsedRealtime() - start)

    private fun describe(result: TotalCaptureResult): Map<String, Any?> = linkedMapOf(
        "iso" to result.get(CaptureResult.SENSOR_SENSITIVITY),
        "exposureTimeNs" to result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        "focalLengthMm" to result.get(CaptureResult.LENS_FOCAL_LENGTH),
        "aperture" to result.get(CaptureResult.LENS_APERTURE),
        "activePhysicalId" to result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
        "hasNoiseProfile" to (result.get(CaptureResult.SENSOR_NOISE_PROFILE) != null),
        "hasLensShadingMap" to (result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) != null),
        "hasDynamicBlackLevel" to (result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) != null),
        "hasNeutralColorPoint" to (result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT) != null),
        "noiseReductionMode" to result.get(CaptureResult.NOISE_REDUCTION_MODE),
        "edgeMode" to result.get(CaptureResult.EDGE_MODE),
    )

    private fun cameraError(code: Int) = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
        else -> code.toString()
    }
}
