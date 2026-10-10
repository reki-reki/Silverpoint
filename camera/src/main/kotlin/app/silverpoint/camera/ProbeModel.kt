package app.silverpoint.camera

/** One RAW capture the probe will attempt. */
data class RawTarget(
    /** Camera ID opened with CameraManager.openCamera. */
    val cameraId: String,
    /** If set, the RAW stream is bound to this physical camera inside the logical camera [cameraId]. */
    val physicalId: String?,
    val width: Int,
    val height: Int,
    /** True when the stream comes from SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION. */
    val maxResolution: Boolean,
) {
    val megapixels: Double get() = width.toLong() * height / 1_000_000.0

    val label: String
        get() = buildString {
            append("id ").append(cameraId)
            if (physicalId != null) append(" / physical ").append(physicalId)
            append(" ").append(width).append('x').append(height)
            append(" (").append(String.format(java.util.Locale.US, "%.1f", megapixels)).append(" MP")
            append(if (maxResolution) ", max-res mode)" else ")")
        }
}

/** Result of one capture attempt. [stage] is the last stage reached: open, session, capture or image. */
data class CaptureOutcome(
    val target: RawTarget,
    val ok: Boolean,
    val stage: String,
    val error: String?,
    val durationMs: Long,
    val details: Map<String, Any?> = emptyMap(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "target" to target.label,
        "cameraId" to target.cameraId,
        "physicalId" to target.physicalId,
        "size" to "${target.width}x${target.height}",
        "megapixels" to Math.round(target.megapixels * 10) / 10.0,
        "maxResolutionMode" to target.maxResolution,
        "ok" to ok,
        "stage" to stage,
        "error" to error,
        "durationMs" to durationMs,
        "details" to details,
    )
}

/** Turns the raw outcomes into the go/no-go answer the project's roadmap asks for. */
object ProbeSummary {
    fun build(outcomes: List<CaptureOutcome>): Map<String, Any?> {
        val ok = outcomes.filter { it.ok }
        val okDefault = ok.filter { !it.target.maxResolution }
        val okMax = ok.filter { it.target.maxResolution }
        val bestDefault = okDefault.maxByOrNull { it.target.megapixels }
        val bestMax = okMax.maxByOrNull { it.target.megapixels }
        val physicalOk = ok.mapNotNull { it.target.physicalId?.let { p -> "${it.target.cameraId}/$p" } }.distinct()
        val physicalFailed = outcomes.filter { !it.ok && it.target.physicalId != null }
            .map { "${it.target.cameraId}/${it.target.physicalId}" }
            .distinct()
            .filter { it !in physicalOk }

        val verdict = when {
            ok.isEmpty() -> "NO-GO: no RAW capture succeeded. Share this report so the cause can be found."
            bestMax != null && bestMax.target.megapixels >= 100 ->
                "FULL: maximum-resolution RAW worked (${bestMax.target.label}). 200 MP can be offered."
            bestDefault != null && bestDefault.target.megapixels >= 40 ->
                "GOOD: ${bestDefault.target.label} worked. 50 MP RAW can be offered."
            bestDefault != null ->
                "GO (baseline): RAW works up to ${fmt(bestDefault.target.megapixels)} MP. " +
                    "50 and 200 MP are not available to this app."
            else -> "PARTIAL: only maximum-resolution RAW worked (${bestMax?.target?.label})."
        }

        return linkedMapOf(
            "verdict" to verdict,
            "attempts" to outcomes.size,
            "succeeded" to ok.size,
            "largestDefaultRawMp" to bestDefault?.let { round1(it.target.megapixels) },
            "largestDefaultRawTarget" to bestDefault?.target?.label,
            "largestMaxResRawMp" to bestMax?.let { round1(it.target.megapixels) },
            "largestMaxResRawTarget" to bestMax?.target?.label,
            "physicalCamerasWithRaw" to physicalOk,
            "physicalCamerasFailed" to physicalFailed,
            "failures" to outcomes.filter { !it.ok }.map { "${it.target.label}: ${it.stage}: ${it.error}" },
        )
    }

    private fun round1(d: Double) = Math.round(d * 10) / 10.0
    private fun fmt(d: Double) = String.format(java.util.Locale.US, "%.1f", d)
}
