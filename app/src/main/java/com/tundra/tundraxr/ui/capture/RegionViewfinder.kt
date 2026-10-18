package com.tundra.tundraxr.ui.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.scene
import com.tundra.tundraxr.R
import com.tundra.tundraxr.capture.RegionFrame
import com.tundra.tundraxr.capture.RegionPhase
import com.tundra.tundraxr.ui.components.MonoText
import com.tundra.tundraxr.ui.components.PillButton
import com.tundra.tundraxr.ui.components.PillIcon
import com.tundra.tundraxr.ui.components.PillText
import com.tundra.tundraxr.ui.theme.Motion
import com.tundra.tundraxr.ui.theme.Radius
import com.tundra.tundraxr.ui.theme.Space
import com.tundra.tundraxr.ui.theme.Tundra
import com.tundra.tundraxr.ui.theme.TundraXRTheme
import com.tundra.tundraxr.ui.theme.XrTokens
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the spatial workspace needs for region capture: the live frame and its controls. */
class RegionHooks(
    val frame: StateFlow<RegionFrame?>,
    /** Starts framing, or cancels it. */
    val toggle: () -> Unit,
    val capture: () -> Unit,
)

/** Room for the Capture / Cancel bar under the frame. */
private val BarWidth = 360.dp
private val BarHeight = XrTokens.TargetPrimary + 16.dp
private const val BarGapMeters = 0.02f

/**
 * The capture frame in the room: two panels in activity space, kept on the world-anchored
 * [frame]. One is the frame itself, transparent inside so the real device shows through; the
 * other is the Capture / Cancel bar under it, shown once the frame is let go.
 *
 * Poses follow the hands at tracking rate, so the frame is collected here rather than through
 * composition, and nothing else recomposes while the engineer frames.
 */
@Composable
fun RegionViewfinder(
    session: Session,
    frame: StateFlow<RegionFrame?>,
    phase: RegionPhase?,
    onCapture: () -> Unit,
    onCancel: () -> Unit,
) {
    val activity = LocalContext.current.findActivity() as? ComponentActivity ?: return
    val density = LocalDensity.current
    val phaseState = remember { mutableStateOf(phase) }
    phaseState.value = phase
    val sizeState = remember { mutableStateOf(0f to 0f) }
    val capture by rememberUpdatedState(onCapture)
    val cancel by rememberUpdatedState(onCancel)
    val barPx = with(density) { IntSize2d(BarWidth.roundToPx(), BarHeight.roundToPx()) }

    val panels = remember(session) {
        val frameView = activity.composeView { FrameOutline(phaseState, sizeState) }
        val barView = activity.composeView { CaptureBar(phaseState, { capture() }, { cancel() }) }
        val space = session.scene.activitySpace
        Panels(
            PanelEntity.create(session, frameView, IntSize2d(640, 480), "RegionFrame", parent = space).apply {
                cornerRadius = 0f
                setEnabled(false)
            },
            PanelEntity.create(session, barView, barPx, "RegionCaptureBar", parent = space).apply {
                cornerRadius = 0f
                setEnabled(false)
            },
        )
    }
    DisposableEffect(panels) { onDispose { panels.dispose() } }

    LaunchedEffect(panels, phase) {
        val scene = session.scene
        val pxPerMeter = scene.virtualPixelDensity.convertMetersToPixels(1f)
        // Activity space may be scaled against the real world; sizes are in its units.
        val unitsPerMeter = scene.perceptionSpace.transformVectorTo(Vector3.Right, scene.activitySpace).length
        val barMeters = FloatSize2d(barPx.width / pxPerMeter, barPx.height / pxPerMeter)
        panels.bar.size = FloatSize2d(barMeters.width * unitsPerMeter, barMeters.height * unitsPerMeter)
        frame.collect { f ->
            val showFrame = f != null && phase != null && phase != RegionPhase.Waiting
            panels.frame.setEnabled(showFrame)
            panels.bar.setEnabled(showFrame && phase == RegionPhase.Locked)
            if (!showFrame) return@collect
            f!!
            panels.frame.setPose(scene.perceptionSpace.transformPoseTo(f.pose, scene.activitySpace))
            panels.frame.size = FloatSize2d(f.width * unitsPerMeter, f.height * unitsPerMeter)
            // Resolution follows the size, in steps, so the outline stays crisp without
            // reallocating the surface on every tracked frame.
            val px = IntSize2d((f.width * pxPerMeter).roundToInt().coerceAtLeast(64), (f.height * pxPerMeter).roundToInt().coerceAtLeast(64))
            val current = panels.frame.sizeInPixels
            if (abs(current.width - px.width) > 24 || abs(current.height - px.height) > 24) panels.frame.sizeInPixels = px
            sizeState.value = f.width to f.height
            // The bar hangs just under the frame, a little forward so it never sits inside it.
            val below = Vector3(0f, -(f.height / 2 + BarGapMeters + barMeters.height / 2), 0.01f)
            val barPose = f.pose compose Pose(below)
            panels.bar.setPose(scene.perceptionSpace.transformPoseTo(barPose, scene.activitySpace))
        }
    }
}

private class Panels(val frame: PanelEntity, val bar: PanelEntity) {
    fun dispose() {
        frame.dispose()
        bar.dispose()
    }
}

/**
 * The frame: the design system's hairline as a double stroke (paper over ink) so it reads on
 * any real background, with heavier corner marks. While the hands are still on it the marks
 * take the agent's cobalt; the fill stays almost clear so the device is seen as it is.
 */
@Composable
private fun FrameOutline(phase: MutableState<RegionPhase?>, size: MutableState<Pair<Float, Float>>) {
    val c = Tundra.colors
    val framing = phase.value == RegionPhase.Framing
    val capturing = phase.value == RegionPhase.Capturing
    val flash by animateFloatAsState(if (capturing) 0.35f else 0f, tween(Motion.Base, easing = Motion.EaseOut), label = "flash")
    val mark = if (framing) c.accentDot else c.raised
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.White.copy(alpha = 0.04f + flash))
            val inset = 3.dp.toPx()
            val r = CornerRadius(Radius.md.toPx())
            val tl = Offset(inset, inset)
            val sz = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            drawRoundRect(c.ink.copy(alpha = 0.55f), tl, sz, r, style = Stroke(3.dp.toPx()))
            drawRoundRect(c.raised, tl, sz, r, style = Stroke(1.5.dp.toPx()))
            corners(mark, inset, 28.dp.toPx(), 5.dp.toPx())
        }
        val (w, h) = size.value
        if (w > 0f) {
            MonoText(
                "%.0f × %.0f cm".format(w * 100, h * 100),
                Modifier.padding(Space.s3).clip(RoundedCornerShape(Radius.xs)).background(c.raised.copy(alpha = 0.9f))
                    .padding(horizontal = Space.s2, vertical = 2.dp),
                color = c.ink,
                small = true,
            )
        }
    }
}

private fun DrawScope.corners(color: Color, inset: Float, length: Float, width: Float) {
    val w = size.width - inset
    val h = size.height - inset
    val len = minOf(length, (w - inset) / 3, (h - inset) / 3)
    listOf(
        Offset(inset, inset) to (1f to 1f), Offset(w, inset) to (-1f to 1f),
        Offset(inset, h) to (1f to -1f), Offset(w, h) to (-1f to -1f),
    ).forEach { (o, d) ->
        drawLine(color, o, Offset(o.x + d.first * len, o.y), width, StrokeCap.Round)
        drawLine(color, o, Offset(o.x, o.y + d.second * len), width, StrokeCap.Round)
    }
}

/** Capture (the view's one filled control) and Cancel, on the island's translucent surface. */
@Composable
private fun CaptureBar(phase: MutableState<RegionPhase?>, onCapture: () -> Unit, onCancel: () -> Unit) {
    val c = Tundra.colors
    val shape = RoundedCornerShape(50)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(shape).background(c.raised.copy(alpha = XrTokens.IslandSurfaceAlpha)).border(1.dp, c.borderControl, shape)
                .padding(Space.s2),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val enabled = phase.value == RegionPhase.Locked
            PillButton(onClick = { if (enabled) onCapture() }, filled = true) { fg ->
                PillIcon(R.drawable.ic_camera, fg)
                PillText("Capture", fg)
            }
            PillButton(onClick = onCancel) { fg ->
                PillIcon(R.drawable.ic_close, fg)
                PillText("Cancel", fg)
            }
        }
    }
}

private fun ComponentActivity.composeView(content: @Composable () -> Unit) = ComposeView(this).apply {
    // The panel's surface lives outside the activity's window, so the view tree owners that
    // Compose looks up have to be handed over explicitly.
    setViewTreeLifecycleOwner(this@composeView)
    setViewTreeViewModelStoreOwner(this@composeView)
    setViewTreeSavedStateRegistryOwner(this@composeView)
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    setContent { TundraXRTheme { content() } }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
