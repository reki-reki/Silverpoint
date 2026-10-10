package app.silverpoint

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraMathTest {
    @Test
    fun thirdsMapToTenthSteps() {
        assertEquals(0, CameraMath.aeSteps(0, 1, 10, -20, 20))
        assertEquals(3, CameraMath.aeSteps(1, 1, 10, -20, 20))
        assertEquals(7, CameraMath.aeSteps(2, 1, 10, -20, 20))
        assertEquals(10, CameraMath.aeSteps(3, 1, 10, -20, 20))
        assertEquals(-20, CameraMath.aeSteps(-6, 1, 10, -20, 20))
        assertEquals(20, CameraMath.aeSteps(6, 1, 10, -20, 20))
    }

    @Test
    fun stepsAreClampedToTheCameraRange() {
        assertEquals(6, CameraMath.aeSteps(6, 1, 3, -6, 6))
        assertEquals(12, CameraMath.aeSteps(6, 1, 6, -12, 12))
        assertEquals(10, CameraMath.aeSteps(6, 1, 10, -10, 10))
    }

    @Test
    fun labelsShowAppliedEv() {
        assertEquals("±0.0 EV", CameraMath.evLabel(0, 1, 10))
        assertEquals("+0.7 EV", CameraMath.evLabel(7, 1, 10))
        assertEquals("-2.0 EV", CameraMath.evLabel(-20, 1, 10))
    }

    @Test
    fun rotationFollowsHowThePhoneIsHeld() {
        assertEquals(90, CameraMath.photoRotation(90, 0)) // portrait
        assertEquals(180, CameraMath.photoRotation(90, 90)) // turned clockwise: landscape, top to the right
        assertEquals(0, CameraMath.photoRotation(90, 270)) // landscape the other way
        assertEquals(270, CameraMath.photoRotation(90, 180)) // upside down
        assertEquals(90, CameraMath.photoRotation(90, 30)) // rounds to nearest quarter turn
        assertEquals(180, CameraMath.photoRotation(90, 50))
        assertEquals(90, CameraMath.photoRotation(90, 350))
        assertEquals(90, CameraMath.photoRotation(90, -1)) // flat on a table: assume portrait
    }

    @Test
    fun tiltIsDistanceFromNearestRightAngle() {
        assertEquals(0f, CameraMath.tiltFromLevel(0), 0f)
        assertEquals(5f, CameraMath.tiltFromLevel(5), 0f)
        assertEquals(-5f, CameraMath.tiltFromLevel(355), 0f)
        assertEquals(-2f, CameraMath.tiltFromLevel(88), 0f)
        assertEquals(10f, CameraMath.tiltFromLevel(280), 0f)
    }

    @Test
    fun whiteBalancePresetFollowsTemperature() {
        assertEquals(android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMath.awbModeFor(null))
        assertEquals(android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, CameraMath.awbModeFor(2800))
        assertEquals(android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, CameraMath.awbModeFor(5500))
        assertEquals(android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_SHADE, CameraMath.awbModeFor(8000))
    }

    @Test
    fun lowLightNeedsHighIsoOrSlowShutter() {
        assertEquals(false, CameraMath.isLowLight(100, 10_000_000))
        assertEquals(true, CameraMath.isLowLight(800, 10_000_000))
        assertEquals(true, CameraMath.isLowLight(200, 40_000_000))
    }

    @Test
    fun framingKeepsThePictureUnderTheTopBarAndCropsTheRightSides() {
        // 3:4 is the whole picture, full width.
        val full = CameraMath.framing(400f, 800f, 4f / 3f)
        assertEquals(0f, full.frameY, 1e-3f)
        assertEquals(400f, full.visW, 1e-3f)
        assertEquals(533.33f, full.visH, 0.1f)
        assertEquals(full.frameH, full.visH, 0.1f)
        // 1:1 is the middle band of the same picture.
        val square = CameraMath.framing(400f, 800f, 1f)
        assertEquals(0f, square.frameY, 1e-3f)
        assertEquals(400f, square.visH, 1e-3f)
        assertEquals(66.67f, square.visY, 0.1f)
        // 9:16 enlarges the picture to its height and crops the sides; still starts at the top.
        val tall = CameraMath.framing(400f, 800f, 16f / 9f)
        assertEquals(0f, tall.frameY, 1e-3f)
        assertEquals(711.1f, tall.visH, 0.1f)
        assertEquals(533.3f, tall.frameW, 0.1f)
        assertEquals(-66.67f, tall.frameX, 0.1f)
        // The middle of what you see is the middle of the picture; the visible left edge is in from the picture's.
        assertEquals(0.5f to 0.5f, tall.toFrame(0.5f, 0.5f).let { (u, v) -> Math.round(u * 1000) / 1000f to Math.round(v * 1000) / 1000f })
        assertEquals(0.125f, tall.toFrame(0f, 0.5f).first, 1e-3f)
        // A short screen: the picture shrinks to fit rather than run under the controls.
        val short = CameraMath.framing(400f, 600f, 16f / 9f)
        assertEquals(600f, short.visH, 1e-3f)
        assertEquals(337.5f, short.visW, 0.1f)
    }

    @Test
    fun cropKeepsTheMiddleAndFollowsOrientation() {
        // Portrait 3060 x 4080.
        assertEquals(listOf(0, 0, 3060, 4080), CameraMath.cropFor(3060, 4080, 4f / 3f).toList())
        assertEquals(listOf(0, 510, 3060, 3060), CameraMath.cropFor(3060, 4080, 1f).toList())
        assertEquals(listOf(0, 127, 3060, 3825), CameraMath.cropFor(3060, 4080, 5f / 4f).toList())
        assertEquals(listOf(382, 0, 2295, 4080), CameraMath.cropFor(3060, 4080, 16f / 9f).toList())
        // Landscape 4080 x 3060: the same shapes turned (16:9, 5:4).
        assertEquals(listOf(0, 382, 4080, 2295), CameraMath.cropFor(4080, 3060, 16f / 9f).toList())
        assertEquals(listOf(127, 0, 3825, 3060), CameraMath.cropFor(4080, 3060, 5f / 4f).toList())
    }
}

