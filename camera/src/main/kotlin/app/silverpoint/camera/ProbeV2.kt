package app.silverpoint.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.os.SystemClock
import android.graphics.ImageFormat

/**
 * Probe v2 tests: the questions the first probe could not answer (OIS, preview + RAW together,
 * zoom-ratio lens switching, burst depth, manual control, DNG output). Blocking; run off the main thread.
 */
internal class ProbeV2(
    private val context: Context,
    private val manager: CameraManager,
    /** Names and folder for everything this run saves. */
    private val run: DiagnosticsRun,
    /** 35 mm-equivalent focal length of the main lens, for naming lenses by zoom (null: unknown). */
    private val mainEquivalentMm: Double?,
) {

    /** Preview + RAW on one route, with 3A warm-up, OIS requested, and optionally a DNG of the still. */
    fun routeTest(logicalId: String, physicalId: String?, writeDng: Boolean): RouteResult {
        val route = if (physicalId == null) logicalId else "$logicalId/$physicalId"
        val logical = manager.getCameraCharacteristics(logicalId)
        val chars = characteristicsFor(logical, physicalId)
        val raw = CharacteristicsDump.rawSizes(chars, maxResolution = false).firstOrNull()
            ?: return RouteResult(route, false, 0, false, false, "not requested", null, null, "no RAW size")
        val preview = previewSize(chars)
            ?: return RouteResult(route, false, 0, false, false, "not requested", null, null, "no YUV size")

        var configured = false
        var frames = 0
        var converged = false
        var stillOk = false
        var ois = "not requested"
        var oisReported: Int? = null
        var dng: String? = null
        val details = linkedMapOf<String, Any?>("rawSize" to "${raw.width}x${raw.height}", "previewSize" to "${preview.first}x${preview.second}")
        var error: String? = null
        try {
            CameraHarness(manager).use { h ->
                val device = h.open(logicalId)
                val sink = h.previewSink(preview.first, preview.second)
                val rawOut = h.rawCollector(raw.width, raw.height, 2)
                val session = h.session(device, outputs(physicalId, sink.surface, rawOut.surface))
                configured = true

                val previewReq = request(device, CameraDevice.TEMPLATE_PREVIEW, physicalId).apply {
                    addTarget(sink.surface)
                    ois = PhysicalKeys.requestOis(this, logical, physicalId, chars)
                }
                val conv = h.repeatUntilConverged(session, previewReq.build())
                converged = conv.settled
                details["convergence"] = describe(conv)

                val still = request(device, CameraDevice.TEMPLATE_STILL_CAPTURE, physicalId).apply {
                    addTarget(rawOut.surface)
                    rawStillDefaults(this)
                    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                    PhysicalKeys.requestOis(this, logical, physicalId, chars)
                }
                val result = h.capture(session, still.build())
                if (rawOut.await(1, 5_000) < 1) throw ProbeException("image", "capture finished but no RAW image arrived")
                stillOk = true
                frames = sink.frames.get()

                val physical = physicalId?.let { result.physicalCameraTotalResults[it] }
                oisReported = physical?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                    ?: result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                details["oisLogicalResult"] = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                details["oisPhysicalResult"] = physical?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                details["still"] = ResultDetails.describe(physical ?: result)
                val image = rawOut.held().first()
                details["image"] = ImageStats.describe(image)

                // The lens that actually took the frame: the bound physical lens, or the one the logical camera chose.
                val lensId = physicalId ?: result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                val lensChars = lensId?.let { CharacteristicsDump.safe { manager.getCameraCharacteristics(it) } } ?: chars
                val label = lensLabel(lensChars)
                val shot = physical ?: result
                val focal = lensChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val aperture = lensChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
                details["lens"] = linkedMapOf(
                    "label" to label?.token,
                    "physicalId" to lensId,
                    "focalLengthMm" to focal?.toDouble(),
                    "aperture" to aperture?.toDouble(),
                    "equivalentMm" to label?.equivalentMm?.let { Math.round(it * 10) / 10.0 },
                )
                if (writeDng) {
                    val description = String.format(
                        java.util.Locale.US,
                        "Silverpoint diagnostics run %s, lens %s (physical %s, %.1f mm f/%.1f, %.0f mm equiv.), route %s, ISO %s, %s",
                        run.runId, label?.token ?: "unknown", lensId ?: "-", focal ?: 0f, aperture ?: 0f,
                        label?.equivalentMm ?: 0.0, route, shot.get(CaptureResult.SENSOR_SENSITIVITY),
                        shot.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let(::shutterText),
                    )
                    // Physical routes pair the lens's characteristics with its own result; the logical route keeps
                    // the logical pair, which is what DngCreator was verified with on the phone.
                    val dngChars = if (physicalId != null) lensChars else chars
                    dng = try {
                        DngWriter.write(
                            context, dngChars, shot, image,
                            run.dngName(label, route),
                            // The probe assumes the phone is held upright (portrait).
                            rotationDegrees = dngChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                            folder = run.folder,
                            description = description,
                        )
                    } catch (e: ProbeException) {
                        details["dngError"] = e.message
                        null
                    }
                }
            }
        } catch (e: ProbeException) {
            error = "${e.stage}: ${e.message}"
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
        }
        return RouteResult(route, configured, frames, converged, stillOk, ois, oisReported, dng, error, details)
    }

    /** RAW stills on the logical camera at several zoom ratios in one session: does the lens follow? */
    fun zoomTest(logicalId: String): List<ZoomResult> {
        val logical = manager.getCameraCharacteristics(logicalId)
        val range = CharacteristicsDump.safe { logical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }
        val ratios = listOf(0.6f, 1f, 2f, 3f, 5f, 10f)
            .filter { range == null || (it >= range.lower - 0.001f && it <= range.upper + 0.001f) }
        val raw = CharacteristicsDump.rawSizes(logical, maxResolution = false).firstOrNull()
            ?: return ratios.map { ZoomResult(it, false, null, null, "no RAW size") }
        val preview = previewSize(logical) ?: return ratios.map { ZoomResult(it, false, null, null, "no YUV size") }

        val out = mutableListOf<ZoomResult>()
        try {
            CameraHarness(manager).use { h ->
                val device = h.open(logicalId)
                val sink = h.previewSink(preview.first, preview.second)
                val rawOut = h.rawCollector(raw.width, raw.height, 2)
                val session = h.session(device, outputs(null, sink.surface, rawOut.surface))
                for (z in ratios) {
                    out += try {
                        val previewReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(sink.surface)
                            set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
                        }
                        h.repeatUntilConverged(session, previewReq.build(), timeoutMs = 2_500)
                        val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(rawOut.surface)
                            rawStillDefaults(this)
                            set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
                        }
                        val result = h.capture(session, still.build())
                        if (rawOut.await(1, 5_000) < 1) throw ProbeException("image", "no RAW image")
                        val image = rawOut.held().first()
                        ZoomResult(
                            z, true, result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
                            "${image.width}x${image.height}", null,
                        )
                    } catch (e: ProbeException) {
                        ZoomResult(z, false, null, null, "${e.stage}: ${e.message}")
                    } finally {
                        rawOut.closeAll()
                    }
                }
            }
        } catch (t: Throwable) {
            val msg = if (t is ProbeException) "${t.stage}: ${t.message}" else "${t.javaClass.simpleName}: ${t.message}"
            val done = out.map { it.zoomRatio }.toSet()
            out += ratios.filter { it !in done }.map { ZoomResult(it, false, null, null, msg) }
        }
        return out
    }

    /** Fixed ISO and shutter with AE off: does the camera use what was asked for? */
    fun manualTest(logicalId: String, physicalId: String?): List<ManualResult> {
        val logical = manager.getCameraCharacteristics(logicalId)
        val chars = characteristicsFor(logical, physicalId)
        val raw = CharacteristicsDump.rawSizes(chars, maxResolution = false).firstOrNull() ?: return emptyList()
        val preview = previewSize(chars) ?: return emptyList()
        val isoRange = CharacteristicsDump.safe { chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }
        val expRange = CharacteristicsDump.safe { chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) }
        val cases = listOf(100 to 20_000_000L, 1600 to 4_000_000L).map { (iso, exp) ->
            (isoRange?.clamp(iso) ?: iso) to (expRange?.clamp(exp) ?: exp)
        }

        val out = mutableListOf<ManualResult>()
        try {
            CameraHarness(manager).use { h ->
                val device = h.open(logicalId)
                val sink = h.previewSink(preview.first, preview.second)
                val rawOut = h.rawCollector(raw.width, raw.height, 2)
                val session = h.session(device, outputs(physicalId, sink.surface, rawOut.surface))
                h.repeatUntilConverged(session, request(device, CameraDevice.TEMPLATE_PREVIEW, physicalId).apply {
                    addTarget(sink.surface)
                }.build())
                for ((iso, exp) in cases) {
                    out += try {
                        val req = request(device, CameraDevice.TEMPLATE_STILL_CAPTURE, physicalId).apply {
                            addTarget(rawOut.surface)
                            rawStillDefaults(this)
                            PhysicalKeys.manualExposure(this, logical, physicalId, iso, exp)
                        }
                        val result = h.capture(session, req.build())
                        rawOut.await(1, 5_000)
                        val r = physicalId?.let { result.physicalCameraTotalResults[it] } ?: result
                        ManualResult(iso, exp, r.get(CaptureResult.SENSOR_SENSITIVITY), r.get(CaptureResult.SENSOR_EXPOSURE_TIME), null)
                    } catch (e: ProbeException) {
                        ManualResult(iso, exp, null, null, "${e.stage}: ${e.message}")
                    } finally {
                        rawOut.closeAll()
                    }
                }
            }
        } catch (t: Throwable) {
            val msg = if (t is ProbeException) "${t.stage}: ${t.message}" else "${t.javaClass.simpleName}: ${t.message}"
            out += cases.drop(out.size).map { (iso, exp) -> ManualResult(iso, exp, null, null, msg) }
        }
        return out
    }

    /**
     * Back-to-back RAW frames at the metered exposure, all held open at once like a merge buffer.
     * Tells us how many frames a multi-frame merge can collect and how fast.
     */
    fun burstTest(logicalId: String, physicalId: String?, sizes: List<Int> = listOf(4, 8, 12)): List<BurstResult> {
        val logical = manager.getCameraCharacteristics(logicalId)
        val chars = characteristicsFor(logical, physicalId)
        val raw = CharacteristicsDump.rawSizes(chars, maxResolution = false).firstOrNull()
            ?: return sizes.map { BurstResult(it, 0, emptyList(), 0, "no RAW size") }
        val preview = previewSize(chars) ?: return sizes.map { BurstResult(it, 0, emptyList(), 0, "no YUV size") }

        val out = mutableListOf<BurstResult>()
        try {
            CameraHarness(manager).use { h ->
                val device = h.open(logicalId)
                val sink = h.previewSink(preview.first, preview.second)
                val rawOut = h.rawCollector(raw.width, raw.height, sizes.max())
                val session = h.session(device, outputs(physicalId, sink.surface, rawOut.surface))
                val conv = h.repeatUntilConverged(session, request(device, CameraDevice.TEMPLATE_PREVIEW, physicalId).apply {
                    addTarget(sink.surface)
                }.build())
                val metered = physicalId?.let { conv.result?.physicalCameraTotalResults?.get(it) } ?: conv.result
                val iso = metered?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 200
                val exp = metered?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L

                for (n in sizes) {
                    val start = SystemClock.elapsedRealtime()
                    out += try {
                        val requests = List(n) {
                            request(device, CameraDevice.TEMPLATE_STILL_CAPTURE, physicalId).apply {
                                addTarget(rawOut.surface)
                                rawStillDefaults(this)
                                PhysicalKeys.manualExposure(this, logical, physicalId, iso, exp)
                            }.build()
                        }
                        h.burst(session, requests, timeoutMs = 3_000L + n * 1_500L)
                        val got = rawOut.await(n, 3_000L + n * 500L)
                        val stamps = rawOut.held().map { it.timestamp }.sorted()
                        val intervals = stamps.zipWithNext { a, b -> (b - a) / 1_000_000 }
                        BurstResult(n, got, intervals, SystemClock.elapsedRealtime() - start, rawOut.overflow)
                    } catch (e: ProbeException) {
                        BurstResult(n, rawOut.held().size, emptyList(), SystemClock.elapsedRealtime() - start, "${e.stage}: ${e.message}")
                    } finally {
                        rawOut.closeAll()
                    }
                }
            }
        } catch (t: Throwable) {
            val msg = if (t is ProbeException) "${t.stage}: ${t.message}" else "${t.javaClass.simpleName}: ${t.message}"
            out += sizes.drop(out.size).map { BurstResult(it, 0, emptyList(), 0, msg) }
        }
        return out
    }

    /**
     * Time to move a running preview + RAW session from lens [from] to lens [to] with the camera
     * kept open: the session is recreated with outputs bound to the new physical ID.
     */
    fun lensSwitchTest(logicalId: String, from: String, to: String): Map<String, Any?> {
        val logical = manager.getCameraCharacteristics(logicalId)
        val out = linkedMapOf<String, Any?>("from" to from, "to" to to)
        try {
            CameraHarness(manager).use { h ->
                val device = h.open(logicalId)
                fun start(physicalId: String): Map<String, Any?> {
                    val chars = characteristicsFor(logical, physicalId)
                    val raw = CharacteristicsDump.rawSizes(chars, maxResolution = false).firstOrNull()
                        ?: throw ProbeException("session", "no RAW size on $physicalId")
                    val preview = previewSize(chars) ?: throw ProbeException("session", "no YUV size on $physicalId")
                    val t0 = SystemClock.elapsedRealtime()
                    val sink = h.previewSink(preview.first, preview.second)
                    val rawOut = h.rawCollector(raw.width, raw.height, 2)
                    val session = h.session(device, outputs(physicalId, sink.surface, rawOut.surface))
                    val sessionMs = SystemClock.elapsedRealtime() - t0
                    val conv = h.repeatUntilConverged(session, request(device, CameraDevice.TEMPLATE_PREVIEW, physicalId).apply {
                        addTarget(sink.surface)
                    }.build())
                    return linkedMapOf(
                        "sessionMs" to sessionMs,
                        "firstFrameMs" to conv.firstFrameMillis?.let { it + sessionMs },
                        "settledMs" to conv.millis + sessionMs,
                        "settled" to conv.settled,
                    )
                }
                out["startOnFrom"] = start(from)
                out["switch"] = start(to)
            }
        } catch (e: ProbeException) {
            out["error"] = "${e.stage}: ${e.message}"
        } catch (t: Throwable) {
            out["error"] = "${t.javaClass.simpleName}: ${t.message}"
        }
        return out
    }

    /** "1/8013 s" below a second, "1.5 s" above. */
    private fun shutterText(ns: Long): String =
        if (ns < 1_000_000_000L) "1/${Math.round(1e9 / ns)} s" else String.format(java.util.Locale.US, "%.1f s", ns / 1e9)

    private fun lensLabel(c: CameraCharacteristics): LensLabel? {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val main = mainEquivalentMm ?: LensLabel.equivalentMm(focal.toDouble(), size.width.toDouble(), size.height.toDouble())
        return LensLabel.of(focal.toDouble(), size.width.toDouble(), size.height.toDouble(), main)
    }

    private fun characteristicsFor(logical: CameraCharacteristics, physicalId: String?): CameraCharacteristics =
        physicalId?.let { CharacteristicsDump.safe { manager.getCameraCharacteristics(it) } } ?: logical

    private fun previewSize(chars: CameraCharacteristics): Pair<Int, Int>? {
        val map = CharacteristicsDump.safe { chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) } ?: return null
        val sizes = CharacteristicsDump.safe { map.getOutputSizes(ImageFormat.YUV_420_888)?.map { it.width to it.height } }
        return PreviewSizes.pick(sizes ?: emptyList())
    }

    private fun outputs(physicalId: String?, vararg surfaces: android.view.Surface): List<OutputConfiguration> =
        surfaces.map { s -> OutputConfiguration(s).also { if (physicalId != null) it.setPhysicalCameraId(physicalId) } }

    /** Physical-camera keys can only be set on a builder created with that physical ID. */
    private fun request(device: CameraDevice, template: Int, physicalId: String?): CaptureRequest.Builder =
        if (physicalId == null) device.createCaptureRequest(template) else device.createCaptureRequest(template, setOf(physicalId))

    private fun rawStillDefaults(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
    }

    private fun describe(c: Convergence): Map<String, Any?> = linkedMapOf(
        "settled" to c.settled,
        "millis" to c.millis,
        "firstFrameMillis" to c.firstFrameMillis,
        "frames" to c.frames,
        "aeState" to c.aeState,
        "awbState" to c.awbState,
        "meteredIso" to c.result?.get(CaptureResult.SENSOR_SENSITIVITY),
        "meteredExposureNs" to c.result?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
    )
}

/** Colour and noise metadata from a capture result, in plain numbers for the report. */
internal object ResultDetails {
    fun describe(r: TotalCaptureResult): Map<String, Any?> = linkedMapOf(
        "iso" to r.get(CaptureResult.SENSOR_SENSITIVITY),
        "exposureTimeNs" to r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        "frameDurationNs" to r.get(CaptureResult.SENSOR_FRAME_DURATION),
        "focalLengthMm" to r.get(CaptureResult.LENS_FOCAL_LENGTH),
        "focusDistanceDiopters" to r.get(CaptureResult.LENS_FOCUS_DISTANCE),
        "activePhysicalId" to r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
        "aeState" to r.get(CaptureResult.CONTROL_AE_STATE),
        "awbState" to r.get(CaptureResult.CONTROL_AWB_STATE),
        "colorCorrectionGains" to CharacteristicsDump.safe {
            r.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { listOf(it.red, it.greenEven, it.greenOdd, it.blue) }
        },
        "neutralColorPoint" to CharacteristicsDump.safe {
            r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toDouble() }
        },
        "noiseProfile" to CharacteristicsDump.safe {
            r.get(CaptureResult.SENSOR_NOISE_PROFILE)?.map { listOf(it.first, it.second) }
        },
        "dynamicBlackLevel" to CharacteristicsDump.safe { r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.toList() },
        "dynamicWhiteLevel" to r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL),
        "lensShadingMapSize" to CharacteristicsDump.safe {
            r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { "${it.columnCount}x${it.rowCount}" }
        },
        "zoomRatio" to r.get(CaptureResult.CONTROL_ZOOM_RATIO),
    )
}
