package app.silverpoint.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeV2SummaryTest {
    private fun route(name: String, ok: Boolean = true, ois: Int? = 1, frames: Int = 10, dng: String? = "f.dng") =
        RouteResult(name, true, frames, true, ok, "physical-key", ois, if (ok) dng else null, if (ok) null else "session: failed")

    @Test
    fun zoomThatChangesLensIsReported() {
        val s = ProbeV2Summary.build(
            emptyList(),
            listOf(ZoomResult(0.6f, true, "2", "4080x3060", null), ZoomResult(1f, true, "5", "4080x3060", null)),
            emptyList(), emptyList(),
        )
        assertTrue(s["zoomSwitching"] as String, (s["zoomSwitching"] as String).startsWith("lens follows zoom ratio (0.6x->2, 1x->5)"))
    }

    @Test
    fun zoomThatKeepsLensMeansSwitchByPhysicalId() {
        val s = ProbeV2Summary.build(
            emptyList(),
            listOf(ZoomResult(1f, true, "5", "4080x3060", null), ZoomResult(3f, true, "5", "4080x3060", null)),
            emptyList(), emptyList(),
        )
        assertTrue((s["zoomSwitching"] as String).contains("switch by physical ID"))
    }

    @Test
    fun largestCompleteBurstIgnoresIncompleteOnes() {
        val s = ProbeV2Summary.build(
            emptyList(), emptyList(),
            listOf(
                BurstResult(4, 4, listOf(33, 34, 33), 500, null),
                BurstResult(8, 8, listOf(33, 33, 33, 33, 33, 33, 33), 900, null),
                BurstResult(12, 9, emptyList(), 9000, "capture: timeout"),
            ),
            emptyList(),
        )
        assertEquals(8, s["largestCompleteBurst"])
        assertEquals(33.0, s["burstMeanIntervalMs"])
    }

    @Test
    fun manualToleranceIsFivePercent() {
        assertTrue(ManualResult(100, 20_000_000, 104, 19_100_000, null).honoured)
        assertFalse(ManualResult(100, 20_000_000, 110, 20_000_000, null).honoured)
        assertFalse(ManualResult(100, 20_000_000, null, null, "capture: failed").honoured)
    }

    @Test
    fun routesSplitIntoWorkingAndFailingAndListOis() {
        val s = ProbeV2Summary.build(
            listOf(route("0/5"), route("0/2", ois = null), route("0/7", ok = false)),
            emptyList(), emptyList(), listOf(ManualResult(100, 1, 100, 1, null)),
        )
        assertEquals(listOf("0/5", "0/2"), s["previewPlusRawWorks"])
        assertEquals(listOf("0/7: session: failed"), s["previewPlusRawFails"])
        assertEquals("ON (physical-key)", (s["oisByRoute"] as Map<*, *>)["0/5"])
        assertEquals("not reported (physical-key)", (s["oisByRoute"] as Map<*, *>)["0/2"])
        assertEquals(listOf("f.dng", "f.dng"), s["dngFilesWritten"])
        assertEquals(true, s["manualControlHonoured"])
    }

    @Test
    fun noPreviewFramesCountsAsFailure() {
        val s = ProbeV2Summary.build(listOf(route("0", frames = 0)), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("0: no preview frames"), s["previewPlusRawFails"])
        assertEquals(null, s["manualControlHonoured"])
    }

    @Test
    fun previewSizePrefersFourThreeWithinFullHd() {
        val sizes = listOf(4080 to 3060, 1920 to 1080, 1440 to 1080, 1280 to 960, 640 to 480)
        assertEquals(1440 to 1080, PreviewSizes.pick(sizes))
    }

    @Test
    fun previewSizeFallsBackToLargestFittingThenSmallest() {
        assertEquals(1920 to 1080, PreviewSizes.pick(listOf(4000 to 3000, 1920 to 1080, 1280 to 720)))
        assertEquals(2000 to 1500, PreviewSizes.pick(listOf(4000 to 3000, 2000 to 1500)))
        assertEquals(null, PreviewSizes.pick(emptyList()))
    }
}
