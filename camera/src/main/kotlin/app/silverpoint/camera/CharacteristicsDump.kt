package app.silverpoint.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Size
import java.util.Locale

/** Reads everything the roadmap's Phase 0 cares about from one camera's CameraCharacteristics. */
object CharacteristicsDump {

    fun dump(ch: CameraCharacteristics, id: String): Map<String, Any?> {
        val caps = safe { ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() } ?: emptyList()
        val physical = safe { ch.physicalCameraIds.sorted() } ?: emptyList()
        val map = safe { ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }
        val mapMax = safe { ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) }
        val requestKeys = safe { ch.availableCaptureRequestKeys } ?: emptyList()

        val out = linkedMapOf<String, Any?>()
        out["id"] = id
        out["facing"] = facing(safe { ch.get(CameraCharacteristics.LENS_FACING) })
        out["hardwareLevel"] = hardwareLevel(safe { ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) })
        out["capabilities"] = caps.map { capability(it) }
        out["hasRaw"] = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        out["hasManualSensor"] = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        out["hasUltraHighResolutionSensor"] =
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
        out["hasRemosaicReprocessing"] =
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING)
        out["supportsSensorPixelModeKey"] = requestKeys.contains(CaptureRequest.SENSOR_PIXEL_MODE)
        out["physicalCameraIds"] = physical
        out["sensorOrientation"] = safe { ch.get(CameraCharacteristics.SENSOR_ORIENTATION) }

        out["sensor"] = linkedMapOf(
            "pixelArray" to size(safe { ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) }),
            "activeArray" to rect(safe { ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) }),
            "preCorrectionActiveArray" to
                rect(safe { ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) }),
            "pixelArrayMaxRes" to size(safe { ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION) }),
            "activeArrayMaxRes" to
                rect(safe { ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION) }),
            "physicalSizeMm" to safe {
                ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { "${it.width} x ${it.height}" }
            },
            "colorFilterArrangement" to cfa(safe { ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) }),
            "whiteLevel" to safe { ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) },
            "blackLevelPattern" to safe {
                ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
                    listOf(
                        p.getOffsetForIndex(0, 0), p.getOffsetForIndex(1, 0),
                        p.getOffsetForIndex(0, 1), p.getOffsetForIndex(1, 1),
                    )
                }
            },
            "isoRange" to range(safe { ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }),
            "exposureTimeRangeNs" to range(safe { ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) }),
            "maxFrameDurationNs" to safe { ch.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) },
            "hasColorTransform1" to (safe { ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) } != null),
            "hasForwardMatrix1" to (safe { ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) } != null),
            "referenceIlluminant1" to safe { ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) },
            "hasColorTransform2" to (safe { ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2) } != null),
            "hasForwardMatrix2" to (safe { ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2) } != null),
            "referenceIlluminant2" to safe { ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() },
            "hasCalibrationTransform1" to (safe { ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1) } != null),
            "timestampSource" to safe { ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) },
        )

        out["requests"] = linkedMapOf(
            "pipelineMaxDepth" to safe { ch.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)?.toInt() },
            "partialResultCount" to safe { ch.get(CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT) },
            "syncMaxLatency" to safe { ch.get(CameraCharacteristics.SYNC_MAX_LATENCY) },
            "physicalCameraRequestKeys" to safe { ch.availablePhysicalCameraRequestKeys?.map { it.name } },
            "focusDistanceCalibration" to safe { ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) },
            "afModes" to safe { ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() },
            "awbModes" to safe { ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() },
        )

        out["lens"] = linkedMapOf(
            "focalLengthsMm" to safe { ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() },
            "apertures" to safe { ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() },
            "minFocusDistanceDiopters" to safe { ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) },
            "hyperfocalDiopters" to safe { ch.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) },
            "opticalStabilizationModes" to
                safe { ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() },
            "zoomRatioRange" to safe {
                ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { "${it.lower} - ${it.upper}" }
            },
        )

        out["exposureControl"] = linkedMapOf(
            "aeCompensationRange" to range(safe { ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) }),
            "aeCompensationStep" to safe { ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toString() },
            "aeAvailableModes" to safe { ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList() },
            "noiseReductionModes" to safe {
                ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList()
            },
            "edgeModes" to safe { ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList() },
            "lensShadingMapModes" to safe {
                ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)?.toList()
            },
            // Android 16 (API 36) additions used for hybrid auto-exposure and Kelvin white balance.
            "aePriorityModes" to if (Build.VERSION.SDK_INT >= 36) {
                safe { ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)?.toList() }
            } else {
                "needs Android 16"
            },
            "colorTemperatureRange" to if (Build.VERSION.SDK_INT >= 36) {
                range(safe { ch.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE) })
            } else {
                "needs Android 16"
            },
        )

        out["streams"] = linkedMapOf(
            "defaultMode" to streams(map),
            "maxResolutionMode" to if (mapMax == null) "not exposed" else streams(mapMax),
        )
        return out
    }

    /** RAW sizes (default stream map) the probe should try, largest first. */
    fun rawSizes(ch: CameraCharacteristics, maxResolution: Boolean): List<Size> {
        val map = safe {
            ch.get(
                if (maxResolution) {
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                } else {
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                },
            )
        } ?: return emptyList()
        return (safe { map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() } ?: emptyList())
            .sortedByDescending { it.width.toLong() * it.height }
    }

    fun supportsMaxResolution(ch: CameraCharacteristics): Boolean =
        safe { ch.availableCaptureRequestKeys.contains(CaptureRequest.SENSOR_PIXEL_MODE) } == true

    private fun streams(map: StreamConfigurationMap?): Map<String, Any?> {
        if (map == null) return mapOf("error" to "no stream configuration map")
        fun sizes(format: Int): List<String> =
            (safe { map.getOutputSizes(format)?.toList() } ?: emptyList())
                .sortedByDescending { it.width.toLong() * it.height }
                .map { "${it.width}x${it.height} (${mp(it)} MP)" }
        return linkedMapOf(
            "RAW_SENSOR" to sizes(ImageFormat.RAW_SENSOR),
            "RAW10" to sizes(ImageFormat.RAW10),
            "RAW12" to sizes(ImageFormat.RAW12),
            "JPEG_largest3" to sizes(ImageFormat.JPEG).take(3),
            "YUV_420_888_largest3" to sizes(ImageFormat.YUV_420_888).take(3),
        )
    }

    private fun mp(s: Size) = String.format(Locale.US, "%.1f", s.width.toLong() * s.height / 1_000_000.0)
    private fun size(s: Size?) = s?.let { "${it.width}x${it.height}" }
    private fun rect(r: Rect?) = r?.let { "${it.left},${it.top} ${it.width()}x${it.height()}" }
    private fun <T : Comparable<T>> range(r: Range<T>?) = r?.let { "${it.lower} - ${it.upper}" }

    private fun facing(v: Int?) = when (v) {
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
        null -> "unknown"
        else -> "other($v)"
    }

    private fun hardwareLevel(v: Int?) = when (v) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        null -> "unknown"
        else -> "other($v)"
    }

    private fun capability(v: Int) = when (v) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "DYNAMIC_RANGE_TEN_BIT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "COLOR_SPACE_PROFILES"
        else -> "UNKNOWN($v)"
    }

    private fun cfa(v: Int?) = when (v) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB (non-Bayer)"
        null -> null
        else -> "other($v)"
    }

    /** Runs [block], returning null instead of throwing: some keys throw on some vendors' HALs. */
    internal inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (_: Throwable) {
        null
    }
}
