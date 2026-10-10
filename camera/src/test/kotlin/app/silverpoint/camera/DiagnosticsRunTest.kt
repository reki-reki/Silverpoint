package app.silverpoint.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsRunTest {
    // S25 Ultra values from the probe reports: focal length, sensor size.
    private val mainEq = LensLabel.equivalentMm(6.3, 9.792, 7.344)

    @Test
    fun s25UltraLensesAreNamedByZoomRelativeToMain() {
        assertEquals("main-1x", LensLabel.of(6.3, 9.792, 7.344, mainEq).token)
        assertEquals("ultrawide-0.6x", LensLabel.of(2.2, 5.712, 4.284, mainEq).token)
        assertEquals("tele-2.7x", LensLabel.of(7.9, 4.48, 3.36, mainEq).token)
        assertEquals("tele-5x", LensLabel.of(18.6, 5.712, 4.284, mainEq).token)
        assertEquals(22.3, mainEq, 0.1)
    }

    @Test
    fun filesOfOneRunShareFolderAndPrefix() {
        val run = DiagnosticsRun("20261009_102615")
        assertEquals("Download/Silverpoint/diag_20261009_102615", run.folder)
        assertEquals("20261009_102615_report.json", run.reportName)
        assertEquals("20261009_102615_main-1x_0-5.dng", run.dngName(LensLabel("main", 1.0, 22.3), "0/5"))
        assertEquals("20261009_102615_lens-unknown_0.dng", run.dngName(null, "0"))
    }

    @Test
    fun runIdIsStartTime() {
        val id = DiagnosticsRun.startingAt(0L).runId
        assertEquals(15, id.length)
        assertEquals('_', id[8])
    }
}
