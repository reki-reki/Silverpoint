package app.silverpoint.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExposureMathTest {
    private val isoRange = 12..3200
    private val nsRange = 83_490L..176_037_266L

    @Test
    fun autoWhenNothingIsSet() {
        assertNull(ExposureMath.forCapture(null, null, 200, 10_000_000, isoRange, nsRange))
    }

    @Test
    fun fullManualIsClamped() {
        assertEquals(3200 to 176_037_266L, ExposureMath.forCapture(6400, 1_000_000_000, 200, 10_000_000, isoRange, nsRange))
    }

    @Test
    fun shutterPriorityKeepsTheMeteredExposure() {
        // Metered ISO 200 at 1/100 s; at 1/400 s the ISO must be 4x.
        assertEquals(800 to 2_500_000L, ExposureMath.forCapture(null, 2_500_000, 200, 10_000_000, isoRange, nsRange))
        // Too dark for the ISO range: ISO clamps at the top.
        assertEquals(3200 to 100_000L, ExposureMath.forCapture(null, 100_000, 800, 30_000_000, isoRange, nsRange))
    }

    @Test
    fun isoPriorityKeepsTheMeteredExposure() {
        assertEquals(100 to 20_000_000L, ExposureMath.forCapture(100, null, 200, 10_000_000, isoRange, nsRange))
    }

    @Test
    fun priorityNeedsAMeteredPreview() {
        assertNull(ExposureMath.forCapture(100, null, null, null, isoRange, nsRange))
    }

    @Test
    fun stopsAreFilteredToTheLensRange() {
        assertEquals(12, ExposureMath.isoStops(12, 3200).first())
        assertEquals(3200, ExposureMath.isoStops(12, 3200).last())
        val shutters = ExposureMath.shutterStops(83_490, 176_037_266)
        assertEquals(125_000L, shutters.first())
        assertEquals(166_666_667L, shutters.last())
        assertEquals("1/250", ExposureMath.shutterLabel(4_000_000))
        assertEquals("0.3 s", ExposureMath.shutterLabel(300_000_000))
        assertEquals("1/4", ExposureMath.shutterLabel(250_000_000))
    }

    @Test
    fun viewPointsMapOntoTheSensorLikeTheDeveloperRotates() {
        // Sensor at 90°: the viewfinder's top-left is the sensor's bottom-left.
        assertEquals(0f to 1f, ExposureMath.viewToSensor(0f, 0f, 90))
        assertEquals(1f to 1f, ExposureMath.viewToSensor(0f, 1f, 90))
        assertEquals(0.5f to 0.5f, ExposureMath.viewToSensor(0.5f, 0.5f, 270))
        assertEquals(0f to 0f, ExposureMath.viewToSensor(0f, 1f, 270))
        assertEquals(1f to 1f, ExposureMath.viewToSensor(0f, 0f, 180))
    }

    @Test
    fun focusLabels() {
        assertEquals("∞", ExposureMath.focusLabel(0f))
        assertEquals("2.0 m", ExposureMath.focusLabel(0.5f))
        assertEquals("10 cm", ExposureMath.focusLabel(10f))
    }
}
