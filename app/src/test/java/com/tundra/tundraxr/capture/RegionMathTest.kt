package com.tundra.tundraxr.capture

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionMathTest {

    private val eps = 1e-3f
    private val head = Pose(Vector3(0f, 1.6f, 0f))
    /** 90° horizontal and vertical FOV, centred, square sensor. */
    private val k = CameraIntrinsics(0.5f, 0.5f, 0.5f, 0.5f, aspect = 1f, calibrated = true)

    @Test
    fun pinchHasHysteresis() {
        val p = PinchDetector()
        val thumb = Vector3(0f, 0f, 0f)
        assertNull(p.update(thumb, Vector3(0.03f, 0f, 0f)))
        assertNotNull(p.update(thumb, Vector3(0.015f, 0f, 0f)))
        // Between the thresholds a held pinch stays held.
        assertNotNull(p.update(thumb, Vector3(0.03f, 0f, 0f)))
        assertNull(p.update(thumb, Vector3(0.04f, 0f, 0f)))
        assertNull(p.update(null, Vector3.Zero))
    }

    @Test
    fun frameSpansBothPinchesAtTheirDepth() {
        val f = RegionMath.frame(Vector3(-0.1f, 1.5f, -0.5f), Vector3(0.1f, 1.7f, -0.5f), head)!!
        assertEquals(0f, f.center.x, eps)
        assertEquals(1.6f, f.center.y, eps)
        assertEquals(-0.5f, f.center.z, eps)
        assertEquals(0.2f, f.width, eps)
        assertEquals(0.2f, f.height, eps)
        assertTrue(f.usable)
        // The frame faces the engineer: its +z points back at the head.
        val normal = f.rotation * Vector3.Backward
        assertEquals(1f, normal.z, eps)
    }

    @Test
    fun pinchesAtDifferentDepthsLandOnOnePlane() {
        // The far point is twice as deep, so it is pulled in along its line of sight.
        val f = RegionMath.frame(Vector3(-0.1f, 1.6f, -0.4f), Vector3(0.2f, 1.6f, -0.8f), head)!!
        assertEquals(-0.6f, f.center.z, eps)
        assertEquals(0.1f * 0.6f / 0.4f + 0.2f * 0.6f / 0.8f, f.width, eps)
    }

    @Test
    fun frameIgnoresHeadRoll() {
        val rolled = Pose(head.translation, Quaternion.fromAxisAngle(Vector3.Forward, 30f))
        val f = RegionMath.frame(Vector3(-0.1f, 1.5f, -0.5f), Vector3(0.1f, 1.7f, -0.5f), rolled)!!
        val right = f.rotation * Vector3.Right
        assertEquals(0f, right.y, eps)
    }

    @Test
    fun noFrameBehindTheHead() {
        assertNull(RegionMath.frame(Vector3(-0.1f, 1.6f, 0.5f), Vector3(0.1f, 1.6f, -0.5f), head))
    }

    @Test
    fun centredFrameCropsTheMiddle() {
        val f = RegionMath.frame(Vector3(-0.1f, 1.5f, -0.5f), Vector3(0.1f, 1.7f, -0.5f), head)!!
        val c = RegionMath.crop(f, head, k, 1000, 1000, margin = 0f)!!
        // ±0.1 m at 0.5 m with 90° FOV is ±0.1 of the image around the centre.
        assertEquals(0.4f, c.left, eps)
        assertEquals(0.6f, c.right, eps)
        assertEquals(0.4f, c.top, eps)
        assertEquals(0.6f, c.bottom, eps)
    }

    @Test
    fun frameAboveCentreCropsTheTopOfThePhoto() {
        val f = RegionMath.frame(Vector3(-0.05f, 1.75f, -0.5f), Vector3(0.05f, 1.85f, -0.5f), head)!!
        val c = RegionMath.crop(f, head, k, 1000, 1000, margin = 0f)!!
        assertTrue(c.bottom < 0.5f)
    }

    @Test
    fun cropFollowsTheHeadAfterLocking() {
        val f = RegionMath.frame(Vector3(-0.1f, 1.5f, -0.5f), Vector3(0.1f, 1.7f, -0.5f), head)!!
        // Head turned left: the locked frame now sits right of centre in the photo.
        val turned = Pose(head.translation, Quaternion.fromAxisAngle(Vector3.Up, 10f))
        val c = RegionMath.crop(f, turned, k, 1000, 1000, margin = 0f)!!
        assertTrue((c.left + c.right) / 2 > 0.5f)
    }

    @Test
    fun narrowerPhotoIsACentredCropOfTheSensor() {
        val f = RegionMath.frame(Vector3(-0.1f, 1.5f, -0.5f), Vector3(0.1f, 1.7f, -0.5f), head)!!
        // A 1:1 sensor saved as a 1:2 portrait photo keeps the middle half of its width.
        val c = RegionMath.crop(f, head, k, 500, 1000, margin = 0f)!!
        assertEquals(0.3f, c.left, eps)
        assertEquals(0.7f, c.right, eps)
    }

    @Test
    fun frameOutOfViewHasNoCrop() {
        val f = RegionMath.frame(Vector3(-0.1f, 1.5f, -0.5f), Vector3(0.1f, 1.7f, -0.5f), head)!!
        val away = Pose(head.translation, Quaternion.fromAxisAngle(Vector3.Up, 120f))
        assertNull(RegionMath.crop(f, away, k, 1000, 1000))
    }

    @Test
    fun uprightIntrinsicsRotateWithTheSensor() {
        val k90 = RegionMath.upright(0.4f, 0.6f, 0.45f, 0.55f, 4000, 3000, 90, calibrated = true)
        assertEquals(0.6f, k90.fx, eps)
        assertEquals(0.4f, k90.fy, eps)
        assertEquals(0.45f, k90.cx, eps)
        assertEquals(0.45f, k90.cy, eps)
        assertEquals(0.75f, k90.aspect, eps)
        assertFalse(RegionMath.upright(0.5f, 0.5f, 0.5f, 0.5f, 10, 10, 0, calibrated = false).calibrated)
    }
}
