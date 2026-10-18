package com.tundra.tundraxr.capture

import android.util.Log
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "TundraRegion"

/** Where a two-hand region capture is. */
enum class RegionPhase {
    /** Tracking hands, no frame yet: pinch with both hands to start one. */
    Waiting,
    /** Both hands pinched; the frame follows them. */
    Framing,
    /** Hands released; the frame stays put in the room until captured, cancelled or redrawn. */
    Locked,
    /** The photo is being taken and cropped. */
    Capturing,
}

/**
 * Two-hand pinch framing on the system's own hand tracking (ARCore for Jetpack XR). Pinch
 * with both hands and the pinch points become opposite corners of a frame in the room; let go
 * and it stays. Hand and head poses are perception space, and so is the frame.
 *
 * Hand tracking costs runtime resources, so it is only configured between [start] and [stop].
 */
class RegionCapture(private val session: Session) {

    private val _phase = MutableStateFlow<RegionPhase?>(null)
    /** Null when region capture is off. */
    val phase: StateFlow<RegionPhase?> = _phase.asStateFlow()

    private val _frame = MutableStateFlow<RegionFrame?>(null)
    val frame: StateFlow<RegionFrame?> = _frame.asStateFlow()

    private val left = PinchDetector()
    private val right = PinchDetector()
    private var job: Job? = null
    private var previousConfig: Config? = null

    /** Turns on hand and head tracking and starts watching for the two-hand pinch. Returns why it can't, or null. */
    fun start(scope: CoroutineScope): String? {
        if (job != null) return null
        val before = session.config
        val result = runCatching {
            session.configure(
                Config.Builder(before)
                    .setHandTracking(HandTrackingMode.BOTH)
                    .setDeviceTracking(DeviceTrackingMode.SPATIAL)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "Configure failed", it) }.getOrNull()
        if (result !is SessionConfigureSuccess) {
            Log.w(TAG, "Hand tracking not configured: $result")
            return if (result == null) "Hand tracking permission needed" else "Hand tracking unavailable on this device"
        }
        previousConfig = before
        val device = ArDevice.getInstance(session)
        left.reset()
        right.reset()
        _frame.value = null
        _phase.value = RegionPhase.Waiting
        job = scope.launch {
            combine(Hand.left(session).state, Hand.right(session).state) { l, r -> l to r }.collect { (l, r) ->
                val head = device.state.value.takeIf { it.trackingState == TrackingState.TRACKING }?.devicePose
                step(left.update(l.tip(HandJointType.THUMB_TIP), l.tip(HandJointType.INDEX_TIP)),
                    right.update(r.tip(HandJointType.THUMB_TIP), r.tip(HandJointType.INDEX_TIP)), head)
            }
        }
        return null
    }

    /** One tracked update: both pinch points (null when that hand isn't pinching) and the head. */
    private fun step(a: Vector3?, b: Vector3?, head: Pose?) {
        val both = a != null && b != null && head != null
        when (_phase.value) {
            RegionPhase.Waiting, RegionPhase.Locked -> if (both) {
                RegionMath.frame(a!!, b!!, head!!)?.let {
                    _frame.value = it
                    _phase.value = RegionPhase.Framing
                }
            }
            RegionPhase.Framing -> if (both) {
                RegionMath.frame(a!!, b!!, head!!)?.let { _frame.value = it }
            } else {
                // One hand opening already shifts its pinch point, so keep the frame from the
                // last update where both were still closed.
                if (_frame.value?.usable == true) {
                    _phase.value = RegionPhase.Locked
                } else {
                    _frame.value = null
                    _phase.value = RegionPhase.Waiting
                }
            }
            RegionPhase.Capturing, null -> Unit
        }
    }

    /** Freezes the locked frame for the shot: the frame, and the head pose at the shutter. */
    fun beginCapture(): Pair<RegionFrame, Pose>? {
        if (_phase.value != RegionPhase.Locked) return null
        val frame = _frame.value ?: return null
        val head = ArDevice.getInstance(session).state.value.devicePose
        _phase.value = RegionPhase.Capturing
        return frame to head
    }

    /** Clears the frame and hands tracking back. */
    fun stop() {
        job?.cancel()
        job = null
        _frame.value = null
        _phase.value = null
        previousConfig?.let { runCatching { session.configure(it) } }
        previousConfig = null
    }

    private fun Hand.State.tip(joint: HandJointType): Vector3? =
        if (trackingState == TrackingState.TRACKING) handJoints[joint]?.translation else null
}
