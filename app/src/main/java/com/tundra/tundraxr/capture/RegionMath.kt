package com.tundra.tundraxr.capture

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Thumb-to-index pinch with hysteresis, so a pinch held near the threshold doesn't flicker.
 * Distances are in meters, as the system reports hand joints.
 */
class PinchDetector(private val closeAt: Float = 0.02f, private val openAt: Float = 0.035f) {
    var pinched = false
        private set

    /** Feeds one tracked frame; returns the pinch point (between the two tips) while pinched. */
    fun update(thumbTip: Vector3?, indexTip: Vector3?): Vector3? {
        if (thumbTip == null || indexTip == null) {
            pinched = false
            return null
        }
        val d = Vector3.distance(thumbTip, indexTip)
        pinched = if (pinched) d < openAt else d < closeAt
        return if (pinched) (thumbTip + indexTip) * 0.5f else null
    }

    fun reset() {
        pinched = false
    }
}

/**
 * The capture frame, fixed in the world: [center] and [rotation] in perception space, with
 * local +x right, +y up and +z towards the engineer (so a panel at this pose faces them).
 * Width and height in meters.
 */
data class RegionFrame(val center: Vector3, val rotation: Quaternion, val width: Float, val height: Float) {
    val pose get() = Pose(center, rotation)

    /** Corners in perception space. */
    fun corners(): List<Vector3> = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (sx, sy) ->
        center + rotation * Vector3(sx * width / 2, sy * height / 2, 0f)
    }

    val usable get() = width >= MinSide && height >= MinSide

    companion object {
        /** Smaller than this the frame is a slip of the hands, not a selection. */
        const val MinSide = 0.04f
    }
}

/**
 * Camera intrinsics as fractions of the upright image (the photo after its EXIF rotation):
 * a point at camera-space (x right, y down, z forward) lands at u = fx·x/z + cx, v = fy·y/z + cy.
 * [aspect] is the upright active array's width / height, which the saved JPEG may crop.
 */
data class CameraIntrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val aspect: Float, val calibrated: Boolean)

/** A crop as fractions of the upright photo, each in 0..1. */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

object RegionMath {

    /** Nearest the frame plane may sit; closer than this the hands are practically at the eyes. */
    private const val MinDepth = 0.2f

    /**
     * The rectangle two pinch points span, as the engineer sees it: both points are carried
     * along their lines of sight onto one plane facing the head (at their mean depth), and
     * the frame takes them as opposite corners. Head roll is dropped so the frame stays level.
     */
    fun frame(a: Vector3, b: Vector3, head: Pose): RegionFrame? {
        val level = levelHead(head) ?: return null
        val toLocal = level.inverse
        val la = (toLocal compose Pose(a)).translation
        val lb = (toLocal compose Pose(b)).translation
        // Behind or beside the eyes: nothing sensible to frame.
        if (-la.z < 0.05f || -lb.z < 0.05f) return null
        val depth = max(MinDepth, (-la.z - lb.z) / 2)
        val pa = la * (depth / -la.z)
        val pb = lb * (depth / -lb.z)
        val local = Vector3((pa.x + pb.x) / 2, (pa.y + pb.y) / 2, -depth)
        return RegionFrame(
            center = (level compose Pose(local)).translation,
            rotation = level.rotation,
            width = abs(pa.x - pb.x),
            height = abs(pa.y - pb.y),
        )
    }

    /**
     * Where [frame] falls in a photo taken from [head], as fractions of the upright image of
     * [imageWidth]×[imageHeight]. The world camera is taken to sit at the head pose (the
     * platform publishes no camera-to-head transform), so the crop is widened by [margin] of
     * its size on each side. Null when the frame is out of the picture.
     */
    fun crop(
        frame: RegionFrame,
        head: Pose,
        k: CameraIntrinsics,
        imageWidth: Int,
        imageHeight: Int,
        margin: Float = 0.08f,
    ): CropRect? {
        val toHead = head.inverse
        // The JPEG may be a centred crop of the active array with a different aspect ratio.
        val imageAspect = imageWidth.toFloat() / imageHeight
        val keepX = if (imageAspect < k.aspect) imageAspect / k.aspect else 1f
        val keepY = if (imageAspect > k.aspect) k.aspect / imageAspect else 1f
        val points = frame.corners().map { corner ->
            val p = (toHead compose Pose(corner)).translation
            // Head space is y up, -z forward; camera space is y down, +z forward.
            val z = -p.z
            if (z < 0.05f) return null
            val u = k.fx * p.x / z + k.cx
            val v = k.fy * -p.y / z + k.cy
            ((u - (1 - keepX) / 2) / keepX) to ((v - (1 - keepY) / 2) / keepY)
        }
        var l = points.minOf { it.first }
        var r = points.maxOf { it.first }
        var t = points.minOf { it.second }
        var b = points.maxOf { it.second }
        val padX = (r - l) * margin
        val padY = (b - t) * margin
        l = max(0f, l - padX); r = min(1f, r + padX)
        t = max(0f, t - padY); b = min(1f, b + padY)
        // Mostly outside the photo: say so rather than send a sliver.
        if (r - l < 0.02f || b - t < 0.02f) return null
        return CropRect(l, t, r, b)
    }

    /** The head pose turned to face the same way but with world up kept up. */
    private fun levelHead(head: Pose): Pose? {
        val forward = head.forward
        if (abs(forward.y) > 0.98f) return null // looking straight up or down
        // fromLookTowards points local +z along its first argument; +z must face the engineer.
        return Pose(head.translation, Quaternion.fromLookTowards(-forward, Vector3.Up))
    }

    /**
     * Intrinsics in the upright image from Camera2's sensor-frame values. [fx]..[cy] are
     * fractions of the sensor-frame active array of [width]×[height]; [sensorRotation] is
     * how far (clockwise) the sensor image turns to be upright.
     */
    fun upright(fx: Float, fy: Float, cx: Float, cy: Float, width: Int, height: Int, sensorRotation: Int, calibrated: Boolean): CameraIntrinsics {
        val aspect = width.toFloat() / height
        return when ((sensorRotation % 360 + 360) % 360) {
            90 -> CameraIntrinsics(fy, fx, 1 - cy, cx, 1 / aspect, calibrated)
            180 -> CameraIntrinsics(fx, fy, 1 - cx, 1 - cy, aspect, calibrated)
            270 -> CameraIntrinsics(fy, fx, cy, 1 - cx, 1 / aspect, calibrated)
            else -> CameraIntrinsics(fx, fy, cx, cy, aspect, calibrated)
        }
    }
}
