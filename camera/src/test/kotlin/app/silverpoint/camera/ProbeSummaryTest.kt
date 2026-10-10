package app.silverpoint.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeSummaryTest {
    private fun ok(w: Int, h: Int, maxRes: Boolean = false, id: String = "0", phys: String? = null) =
        CaptureOutcome(RawTarget(id, phys, w, h, maxRes), true, "image", null, 100)

    private fun bad(w: Int, h: Int, maxRes: Boolean = false, id: String = "0", phys: String? = null) =
        CaptureOutcome(RawTarget(id, phys, w, h, maxRes), false, "session", "boom", 100)

    @Test
    fun noSuccessIsNoGo() {
        val s = ProbeSummary.build(listOf(bad(4000, 3000)))
        assertTrue((s["verdict"] as String).startsWith("NO-GO"))
        assertEquals(0, s["succeeded"])
    }

    @Test
    fun twelveMpOnlyIsBaselineGo() {
        val s = ProbeSummary.build(listOf(ok(4000, 3000), bad(8160, 6120, true)))
        assertTrue(s["verdict"] as String, (s["verdict"] as String).startsWith("GO (baseline)"))
        assertEquals(12.0, s["largestDefaultRawMp"])
        assertEquals(null, s["largestMaxResRawMp"])
    }

    @Test
    fun fiftyMpDefaultIsGood() {
        val s = ProbeSummary.build(listOf(ok(4000, 3000), ok(8160, 6120)))
        assertTrue((s["verdict"] as String).startsWith("GOOD"))
        assertEquals(49.9, s["largestDefaultRawMp"])
    }

    @Test
    fun twoHundredMpMaxResIsFull() {
        val s = ProbeSummary.build(listOf(ok(4000, 3000), ok(16320, 12240, true)))
        assertTrue((s["verdict"] as String).startsWith("FULL"))
        assertEquals(199.8, s["largestMaxResRawMp"])
    }

    @Test
    fun physicalRoutesAreListedAndFailuresDeduplicated() {
        val s = ProbeSummary.build(
            listOf(ok(4000, 3000, id = "0", phys = "2"), bad(4000, 3000, id = "0", phys = "4"), bad(1000, 1000, id = "0", phys = "4")),
        )
        assertEquals(listOf("0/2"), s["physicalCamerasWithRaw"])
        assertEquals(listOf("0/4"), s["physicalCamerasFailed"])
    }

    @Test
    fun textReportRendersNestedStructures() {
        val text = ReportText.render(linkedMapOf("a" to mapOf("b" to listOf(1, 2)), "c" to listOf(mapOf("d" to 1))))
        assertTrue(text, text.contains("a:\n  b: [1, 2]\n"))
        assertTrue(text, text.contains("c:\n  [0]\n    d: 1\n"))
    }
}
