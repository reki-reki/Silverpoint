package app.silverpoint.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Phase 0 capability probe: dumps CameraCharacteristics for every camera and tries a RAW capture on
 * each reachable route (direct camera ID, and each physical camera inside a logical one) at each
 * advertised RAW size, in default and maximum-resolution mode.
 *
 * Blocking; run it off the main thread. Needs the CAMERA permission.
 */
class ProbeRunner(
    private val context: Context,
    private val appVersion: String,
    private val run: DiagnosticsRun = DiagnosticsRun.startingAt(System.currentTimeMillis()),
) {

    fun run(onProgress: (String) -> Unit): Map<String, Any?> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList.toList()
        onProgress("Found ${ids.size} camera IDs: ${ids.joinToString()}")

        val chars = linkedMapOf<String, CameraCharacteristics>()
        for (id in ids) chars[id] = manager.getCameraCharacteristics(id)

        val cameraDumps = ids.map { id ->
            onProgress("Reading characteristics for camera $id")
            CharacteristicsDump.dump(chars.getValue(id), id)
        }

        // Physical cameras behind each logical camera. Their own characteristics may or may not be readable.
        val physicalDumps = mutableListOf<Map<String, Any?>>()
        val physicalChars = linkedMapOf<String, CameraCharacteristics?>()
        for ((logicalId, ch) in chars) {
            val phys = CharacteristicsDump.safe { ch.physicalCameraIds.sorted() } ?: emptyList()
            for (p in phys) {
                val pch = CharacteristicsDump.safe { manager.getCameraCharacteristics(p) }
                physicalChars["$logicalId/$p"] = pch
                physicalDumps += if (pch != null) {
                    linkedMapOf("route" to "$logicalId/$p", "characteristics" to CharacteristicsDump.dump(pch, p))
                } else {
                    linkedMapOf("route" to "$logicalId/$p", "characteristics" to "not readable by this app")
                }
            }
        }

        val targets = buildTargets(chars, physicalChars)
        onProgress("Will try ${targets.size} RAW captures. This can take a few minutes; keep the app open.")
        val tester = RawCaptureTest(manager)
        val outcomes = mutableListOf<CaptureOutcome>()
        targets.forEachIndexed { i, t ->
            onProgress("[${i + 1}/${targets.size}] ${t.label}")
            val o = tester.run(t)
            outcomes += o
            onProgress(if (o.ok) "    OK in ${o.durationMs} ms" else "    FAILED at ${o.stage}: ${o.error}")
        }

        val v2 = runV2(manager, chars, onProgress)

        val report = linkedMapOf<String, Any?>()
        report["probeVersion"] = PROBE_VERSION
        report["summary"] = ProbeSummary.build(outcomes)
        report["summaryV2"] = v2["summary"]
        report["probe"] = linkedMapOf(
            "app" to "Silverpoint probe $appVersion",
            "runId" to run.runId,
            "folder" to run.folder,
            "reportFile" to run.reportName,
            "timeUtc" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()),
        )
        report["device"] = deviceInfo()
        report["files"] = (v2["routes"] as? List<*>)?.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val file = m["dngFile"] ?: return@mapNotNull null
            val details = m["details"] as? Map<*, *>
            val lens = details?.get("lens") as? Map<*, *>
            val still = details?.get("still") as? Map<*, *>
            linkedMapOf(
                "file" to file,
                "route" to m["route"],
                "lens" to lens?.get("label"),
                "physicalId" to lens?.get("physicalId"),
                "focalLengthMm" to lens?.get("focalLengthMm"),
                "equivalentMm" to lens?.get("equivalentMm"),
                "iso" to still?.get("iso"),
                "exposureTimeNs" to still?.get("exposureTimeNs"),
            )
        } ?: emptyList<Any>()
        report["captureTests"] = outcomes.map { it.toMap() }
        report["v2Tests"] = v2.filterKeys { it != "summary" }
        report["cameras"] = cameraDumps
        report["physicalCameras"] = physicalDumps
        return report
    }

    /**
     * Probe v2: on the back logical multi-camera (or the first back RAW camera), tests each lens route
     * with preview + RAW + OIS (writing one DNG each), zoom-ratio switching, manual control and bursts.
     */
    private fun runV2(
        manager: CameraManager,
        chars: Map<String, CameraCharacteristics>,
        onProgress: (String) -> Unit,
    ): Map<String, Any?> {
        val back = chars.filterValues {
            CharacteristicsDump.safe { it.get(CameraCharacteristics.LENS_FACING) } == CameraCharacteristics.LENS_FACING_BACK &&
                supportsRaw(it)
        }
        val logicalId = back.entries.firstOrNull { (_, ch) ->
            (CharacteristicsDump.safe { ch.physicalCameraIds } ?: emptySet()).isNotEmpty()
        }?.key ?: back.keys.firstOrNull()
        if (logicalId == null) {
            onProgress("Probe v2 skipped: no back camera with RAW")
            return mapOf("summary" to "skipped: no back camera with RAW")
        }
        val physical = CharacteristicsDump.safe { chars.getValue(logicalId).physicalCameraIds.sorted() } ?: emptyList()
        val main = mainLens(manager, physical)
        val mainEq = main?.let { id ->
            CharacteristicsDump.safe {
                val c = manager.getCameraCharacteristics(id)
                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first().toDouble()
                val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
                LensLabel.equivalentMm(f, sz.width.toDouble(), sz.height.toDouble())
            }
        }
        val v2 = ProbeV2(context, manager, run, mainEq)

        val routes = (listOf<String?>(null) + physical).map { p ->
            onProgress("v2: preview + RAW + OIS on ${if (p == null) "camera $logicalId" else "lens $p"} (saves a DNG)")
            v2.routeTest(logicalId, p, writeDng = true).also { r ->
                onProgress(if (r.stillOk) "    OK, OIS ${r.oisReported ?: "not reported"}" else "    FAILED: ${r.error}")
            }
        }
        onProgress("v2: zoom-ratio lens switching on camera $logicalId")
        val zooms = v2.zoomTest(logicalId).onEach { z ->
            onProgress("    ${z.zoomRatio}x: " + if (z.ok) "lens ${z.activePhysicalId}, ${z.imageSize}" else "FAILED ${z.error}")
        }
        onProgress("v2: manual ISO/shutter on ${main?.let { "lens $it" } ?: "camera $logicalId"}")
        val manual = v2.manualTest(logicalId, main).onEach { m ->
            onProgress("    ISO ${m.requestedIso} / ${m.requestedExposureNs / 1000} us -> ${m.reportedIso} / ${m.reportedExposureNs?.div(1000)} us")
        }
        onProgress("v2: RAW bursts of 4, 8 and 12 frames")
        val bursts = v2.burstTest(logicalId, main).onEach { b ->
            onProgress("    ${b.requested}: received ${b.received}" + (b.error?.let { ", $it" } ?: ""))
        }
        val tele = physical.filter { it != main }.maxByOrNull { id ->
            CharacteristicsDump.safe {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.maxOrNull()
            } ?: 0f
        }
        val lensSwitch = if (main != null && tele != null) {
            onProgress("v3: lens switch time, lens $main to lens $tele")
            v2.lensSwitchTest(logicalId, main, tele).also { onProgress("    ${it["switch"] ?: it["error"]}") }
        } else {
            mapOf("skipped" to "needs two physical lenses")
        }
        return linkedMapOf(
            "summary" to ProbeV2Summary.build(routes, zooms, bursts, manual) + ("lensSwitch" to lensSwitch["switch"]),
            "lensSwitch" to lensSwitch,
            "logicalCameraId" to logicalId,
            "mainLensGuess" to main,
            "routes" to routes.map { it.toMap() },
            "zoom" to zooms.map { it.toMap() },
            "manual" to manual.map { it.toMap() },
            "burst" to bursts.map { it.toMap() },
        )
    }

    /** The main lens is taken to be the physical camera with the largest sensor. */
    private fun mainLens(manager: CameraManager, physical: List<String>): String? = physical.maxByOrNull { id ->
        CharacteristicsDump.safe {
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { it.width * it.height }
        } ?: 0f
    }

    private fun buildTargets(
        chars: Map<String, CameraCharacteristics>,
        physicalChars: Map<String, CameraCharacteristics?>,
    ): List<RawTarget> {
        val targets = mutableListOf<RawTarget>()
        val backFirst = chars.entries.sortedBy { (_, ch) ->
            if (CharacteristicsDump.safe { ch.get(CameraCharacteristics.LENS_FACING) } == CameraCharacteristics.LENS_FACING_BACK) 0 else 1
        }
        for ((id, ch) in backFirst) {
            if (!supportsRaw(ch)) continue
            addSizes(targets, id, null, ch)
        }
        // Physical routes: use the physical camera's own sizes if readable, else the logical camera's.
        for ((id, ch) in backFirst) {
            val phys = CharacteristicsDump.safe { ch.physicalCameraIds.sorted() } ?: continue
            for (p in phys) {
                val sizesFrom = physicalChars["$id/$p"] ?: ch
                if (!supportsRaw(sizesFrom) && physicalChars["$id/$p"] != null) continue
                addSizes(targets, id, p, sizesFrom)
            }
        }
        return targets.distinct()
    }

    private fun addSizes(out: MutableList<RawTarget>, id: String, physicalId: String?, sizesFrom: CameraCharacteristics) {
        for (s in CharacteristicsDump.rawSizes(sizesFrom, maxResolution = false)) {
            out += RawTarget(id, physicalId, s.width, s.height, maxResolution = false)
        }
        if (CharacteristicsDump.supportsMaxResolution(sizesFrom)) {
            for (s in CharacteristicsDump.rawSizes(sizesFrom, maxResolution = true)) {
                out += RawTarget(id, physicalId, s.width, s.height, maxResolution = true)
            }
        }
    }

    private fun supportsRaw(ch: CameraCharacteristics): Boolean =
        CharacteristicsDump.safe { ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) } == true ||
            CharacteristicsDump.rawSizes(ch, false).isNotEmpty()

    private fun deviceInfo(): Map<String, Any?> = linkedMapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "device" to Build.DEVICE,
        "soc" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}",
        "android" to Build.VERSION.RELEASE,
        "sdkInt" to Build.VERSION.SDK_INT,
        "securityPatch" to Build.VERSION.SECURITY_PATCH,
        "buildDisplayId" to Build.DISPLAY,
        "oneUiSemPlatformInt" to semPlatformInt(),
        "fingerprint" to Build.FINGERPRINT,
    )

    /** Samsung exposes One UI as SEM_PLATFORM_INT (e.g. 150000 is One UI 7.0); reflection keeps this portable. */
    private fun semPlatformInt(): Int? = try {
        Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null)
    } catch (_: Throwable) {
        null
    }
}
