package app.silverpoint.camera

/** Version of the report format. Bump when fields change meaning so reports stay comparable. */
const val PROBE_VERSION = 4

/** One lens route tested with a live preview, 3A warm-up, OIS request and a RAW still. */
data class RouteResult(
    /** "0" for the logical camera on its own, "0/5" for physical camera 5 inside logical camera 0. */
    val route: String,
    val previewConfigured: Boolean,
    val previewFrames: Int,
    val converged: Boolean,
    val stillOk: Boolean,
    /** How OIS was requested: "logical-key", "physical-key", or why it was not. */
    val oisRequest: String,
    /** LENS_OPTICAL_STABILIZATION_MODE reported back (logical result, then physical result if any). */
    val oisReported: Int?,
    val dngFile: String?,
    val error: String?,
    val details: Map<String, Any?> = emptyMap(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "route" to route,
        "previewConfigured" to previewConfigured,
        "previewFrames" to previewFrames,
        "aeAwbConverged" to converged,
        "stillOk" to stillOk,
        "oisRequest" to oisRequest,
        "oisReported" to oisReported?.let { if (it == 1) "ON" else "OFF" },
        "dngFile" to dngFile,
        "error" to error,
        "details" to details,
    )
}

/** RAW still captured on the logical camera at one zoom ratio, without reconfiguring the session. */
data class ZoomResult(
    val zoomRatio: Float,
    val ok: Boolean,
    val activePhysicalId: String?,
    val imageSize: String?,
    val error: String?,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "zoomRatio" to zoomRatio.toDouble(),
        "ok" to ok,
        "activePhysicalId" to activePhysicalId,
        "imageSize" to imageSize,
        "error" to error,
    )
}

/** A burst of [requested] RAW frames that the app tries to hold open at the same time. */
data class BurstResult(
    val requested: Int,
    val received: Int,
    val intervalsMs: List<Long>,
    val totalMs: Long,
    val error: String?,
) {
    val complete: Boolean get() = error == null && received == requested
    val meanIntervalMs: Double? get() = if (intervalsMs.isEmpty()) null else intervalsMs.average()

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "requested" to requested,
        "received" to received,
        "complete" to complete,
        "meanIntervalMs" to meanIntervalMs?.let { Math.round(it * 10) / 10.0 },
        "maxIntervalMs" to intervalsMs.maxOrNull(),
        "totalMs" to totalMs,
        "error" to error,
    )
}

/** Manual ISO and exposure time requested with AE off, and what the camera reported using. */
data class ManualResult(
    val requestedIso: Int,
    val requestedExposureNs: Long,
    val reportedIso: Int?,
    val reportedExposureNs: Long?,
    val error: String?,
) {
    /** Within 5%: sensors round to their own steps, so exact equality is not expected. */
    val honoured: Boolean
        get() = error == null && reportedIso != null && reportedExposureNs != null &&
            within(reportedIso.toDouble(), requestedIso.toDouble()) &&
            within(reportedExposureNs.toDouble(), requestedExposureNs.toDouble())

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "requestedIso" to requestedIso,
        "reportedIso" to reportedIso,
        "requestedExposureNs" to requestedExposureNs,
        "reportedExposureNs" to reportedExposureNs,
        "honoured" to honoured,
        "error" to error,
    )

    private fun within(got: Double, want: Double) = Math.abs(got - want) <= want * 0.05
}

/** Turns the probe v2 results into the answers the roadmap needs before Phase 1. */
object ProbeV2Summary {
    fun build(
        routes: List<RouteResult>,
        zooms: List<ZoomResult>,
        bursts: List<BurstResult>,
        manual: List<ManualResult>,
    ): Map<String, Any?> {
        val okZooms = zooms.filter { it.ok }
        val lensesSeen = okZooms.mapNotNull { it.activePhysicalId }.distinct()
        val zoomVerdict = when {
            zooms.isEmpty() -> "not tested"
            okZooms.isEmpty() -> "RAW capture failed at every zoom ratio"
            lensesSeen.size > 1 -> "lens follows zoom ratio (${okZooms.joinToString { "${fmt(it.zoomRatio)}x->${it.activePhysicalId}" }}): " +
                "lenses can be switched without reconfiguring"
            else -> "zoom ratio does not switch lens (always ${lensesSeen.firstOrNull() ?: "unknown"}): switch by physical ID"
        }
        val bestBurst = bursts.filter { it.complete }.maxByOrNull { it.requested }
        return linkedMapOf(
            "oisByRoute" to routes.associate { r ->
                r.route to when (r.oisReported) {
                    1 -> "ON (${r.oisRequest})"
                    0 -> "OFF (${r.oisRequest})"
                    else -> "not reported (${r.oisRequest})"
                }
            },
            "previewPlusRawWorks" to routes.filter { it.previewConfigured && it.previewFrames > 0 && it.stillOk }.map { it.route },
            "previewPlusRawFails" to routes.filter { !(it.previewConfigured && it.previewFrames > 0 && it.stillOk) }
                .map { "${it.route}: ${it.error ?: "no preview frames"}" },
            "zoomSwitching" to zoomVerdict,
            "largestCompleteBurst" to bestBurst?.requested,
            "burstMeanIntervalMs" to bestBurst?.meanIntervalMs?.let { Math.round(it * 10) / 10.0 },
            "manualControlHonoured" to if (manual.isEmpty()) null else manual.all { it.honoured },
            "dngFilesWritten" to routes.mapNotNull { it.dngFile },
        )
    }

    private fun fmt(f: Float) = if (f == Math.round(f).toFloat()) f.toInt().toString() else f.toString()
}

/** Chooses a viewfinder-sized YUV stream: at most 1920x1080 pixels, preferring 4:3, then the largest. */
object PreviewSizes {
    fun pick(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? {
        val fits = sizes.filter { (w, h) -> w.toLong() * h <= 1920L * 1080 }
        if (fits.isEmpty()) return sizes.minByOrNull { (w, h) -> w.toLong() * h }
        val fourThree = fits.filter { (w, h) -> Math.abs(w.toDouble() / h - 4.0 / 3.0) < 0.01 }
        return (fourThree.ifEmpty { fits }).maxByOrNull { (w, h) -> w.toLong() * h }
    }
}

/**
 * One Diagnostics run. Everything it saves goes into [folder] with names starting with [runId],
 * so a report and its DNGs always travel together.
 */
data class DiagnosticsRun(val runId: String) {
    /** MediaStore relative path, for example `Download/Silverpoint/diag_20261009_102615`. */
    val folder: String get() = "Download/Silverpoint/diag_$runId"

    val reportName: String get() = "${runId}_report.json"

    /** `20261009_102615_main-1x_0-5.dng`: run, lens, then the camera route the frame came through. */
    fun dngName(lens: LensLabel?, route: String): String =
        "${runId}_${lens?.token ?: "lens-unknown"}_${route.replace('/', '-')}.dng"

    companion object {
        fun startingAt(millis: Long): DiagnosticsRun =
            DiagnosticsRun(java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(millis)))
    }
}

/**
 * A human name for a lens: kind (ultrawide, main, tele) and zoom relative to the main lens, from its
 * 35 mm-equivalent focal length. The S25 Ultra's lenses come out as ultrawide-0.6x, main-1x,
 * tele-2.7x and tele-5x.
 */
data class LensLabel(val kind: String, val zoom: Double, val equivalentMm: Double) {
    val zoomText: String
        get() = if (zoom >= 1 && Math.abs(zoom - Math.rint(zoom)) < 0.15) {
            Math.rint(zoom).toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", zoom)
        }

    /** File-name friendly form, for example `tele-2.7x`. */
    val token: String get() = "$kind-${zoomText}x"

    companion object {
        /** Diagonal of a 36x24 mm frame. */
        private const val FULL_FRAME_DIAGONAL_MM = 43.2666

        fun equivalentMm(focalMm: Double, sensorWidthMm: Double, sensorHeightMm: Double): Double =
            focalMm * FULL_FRAME_DIAGONAL_MM / Math.hypot(sensorWidthMm, sensorHeightMm)

        fun of(focalMm: Double, sensorWidthMm: Double, sensorHeightMm: Double, mainEquivalentMm: Double): LensLabel {
            val eq = equivalentMm(focalMm, sensorWidthMm, sensorHeightMm)
            val zoom = eq / mainEquivalentMm
            val kind = when {
                zoom < 0.8 -> "ultrawide"
                zoom <= 1.25 -> "main"
                else -> "tele"
            }
            return LensLabel(kind, zoom, eq)
        }
    }
}
