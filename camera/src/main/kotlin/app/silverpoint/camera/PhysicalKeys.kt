package app.silverpoint.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle

/**
 * Sets request keys so that a physical lens inside a logical multi-camera honours them.
 *
 * On the S25 Ultra (probe v2) settings sent only on the logical request were ignored by the
 * physical lens for AE mode, ISO and shutter, and OIS could only be enabled per lens. So every
 * setting that the logical camera lists in `availablePhysicalCameraRequestKeys` is sent both ways.
 * Builders must be created with `createCaptureRequest(template, setOf(physicalId))`.
 */
object PhysicalKeys {

    fun <T> set(b: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T, physicalId: String?, logical: CameraCharacteristics) {
        b.set(key, value)
        if (physicalId != null && key in keys(logical)) b.setPhysicalCameraKey(key, value, physicalId)
    }

    /**
     * Turns OIS on where the hardware has it. Returns how it was requested: "physical-key",
     * "logical-key", or why it was not.
     */
    fun requestOis(
        b: CaptureRequest.Builder,
        logical: CameraCharacteristics,
        physicalId: String?,
        lens: CameraCharacteristics,
    ): String {
        val on = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
        if (physicalId != null && oisModes(lens).contains(on) && CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE in keys(logical)) {
            b.setPhysicalCameraKey(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, on, physicalId)
            return "physical-key"
        }
        if (oisModes(logical).contains(on)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, on)
            return "logical-key"
        }
        return if (physicalId != null && oisModes(lens).contains(on)) {
            "not settable (lens has OIS, but the logical camera does not accept it as a physical key)"
        } else {
            "not available"
        }
    }

    /** Fixed ISO and exposure time with auto-exposure off, on the logical request and the lens. */
    fun manualExposure(b: CaptureRequest.Builder, logical: CameraCharacteristics, physicalId: String?, iso: Int, exposureNs: Long) {
        set(b, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO, physicalId, logical)
        set(b, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF, physicalId, logical)
        set(b, CaptureRequest.SENSOR_SENSITIVITY, iso, physicalId, logical)
        set(b, CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs, physicalId, logical)
        set(b, CaptureRequest.SENSOR_FRAME_DURATION, exposureNs, physicalId, logical)
    }

    /**
     * A metering or focus region around viewfinder point ([u], [v]), 0..1 from the top-left, about
     * a tenth of the frame wide. Each request gets the rectangle in its own sensor coordinates: the
     * logical camera's active array and the lens's.
     */
    fun setRegions(b: CaptureRequest.Builder, key: CaptureRequest.Key<Array<MeteringRectangle>>, u: Float, v: Float, l: Lens) {
        val (sx, sy) = ExposureMath.viewToSensor(u, v, l.sensorOrientation)
        fun rect(a: Rect): Array<MeteringRectangle> {
            val w = (a.width() * REGION_FRACTION).toInt()
            val h = (a.height() * REGION_FRACTION).toInt()
            val x = (a.left + sx * a.width() - w / 2).toInt().coerceIn(a.left, a.right - w)
            val y = (a.top + sy * a.height() - h / 2).toInt().coerceIn(a.top, a.bottom - h)
            return arrayOf(MeteringRectangle(x, y, w, h, MeteringRectangle.METERING_WEIGHT_MAX))
        }
        b.set(key, rect(l.logicalActiveArray))
        if (l.physicalId != null && key in keys(l.logical)) b.setPhysicalCameraKey(key, rect(l.activeArray), l.physicalId)
    }

    private const val REGION_FRACTION = 0.1f

    fun oisModes(c: CameraCharacteristics): List<Int> =
        CharacteristicsDump.safe { c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() } ?: emptyList()

    private fun keys(c: CameraCharacteristics): List<CaptureRequest.Key<*>> =
        CharacteristicsDump.safe { c.availablePhysicalCameraRequestKeys } ?: emptyList()
}
