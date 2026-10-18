package com.tundra.tundraxr.ui.island

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SpatialArrangement
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.unit.DpVolumeSize
import androidx.xr.scenecore.SpatialEnvironment
import androidx.xr.scenecore.scene
import com.tundra.tundraxr.Actions
import com.tundra.tundraxr.BuildConfig
import com.tundra.tundraxr.SessionState
import com.tundra.tundraxr.ui.capture.RegionHooks
import com.tundra.tundraxr.ui.capture.RegionViewfinder
import com.tundra.tundraxr.ui.components.DotPattern
import com.tundra.tundraxr.ui.theme.Tundra
import com.tundra.tundraxr.ui.theme.XrTokens

// Focused panels adapt to any size in between; these just stop them becoming unusable.
private val FocusedMin = DpVolumeSize(320.dp, 300.dp, 0.dp)
private val FocusedMax = DpVolumeSize(2600.dp, 1800.dp, 0.dp)

/**
 * Where the island is (open or not) and what the focused panel shows. UI state only: it lives
 * here, apart from the session, and survives rotation. Closing the focused panel returns to the
 * open island.
 */
private class IslandUi(val expanded: MutableState<Boolean>, val focused: MutableState<FocusedContent?>) {
    fun open(content: FocusedContent) {
        focused.value = content
    }

    fun toggle(content: FocusedContent) {
        focused.value = if (focused.value == content) null else content
    }

    fun closeFocused() {
        focused.value = null
        expanded.value = true
    }
}

@Composable
private fun rememberIslandUi() = IslandUi(
    rememberSaveable { mutableStateOf(false) },
    rememberSaveable(stateSaver = FocusedContent.Saver) { mutableStateOf<FocusedContent?>(null) },
)

/** The island's callbacks, each onto an existing [Actions] entry or a change of UI state. */
private fun islandActions(ui: IslandUi, actions: Actions, switchSpace: (() -> Unit)?, switchToFull: Boolean, region: (() -> Unit)? = null) = IslandActions(
    expand = { ui.expanded.value = true },
    collapse = { ui.expanded.value = false },
    askVoice = actions.askVoice,
    photo = actions.photo,
    record = actions.record,
    region = region,
    chat = { ui.toggle(FocusedContent.Chat) },
    voiceNote = actions.note,
    manual = { ui.toggle(FocusedContent.Manual) },
    draft = actions.draft,
    send = actions.send,
    ask = actions.ask,
    source = { s ->
        // A cited page opens only when the engineer asks for it; answers never pop the manual up.
        if (s.manualTarget != null) {
            actions.openSource(s)
            ui.open(FocusedContent.Manual)
        }
    },
    openGuidance = { g ->
        actions.openManual(g.target)
        ui.open(FocusedContent.Manual)
    },
    openImage = { img -> ui.open(FocusedContent.Picture(img.file, img.label)) },
    openChat = { ui.open(FocusedContent.Chat) },
    openSession = { ui.open(FocusedContent.Session) },
    selectDevice = actions.selectDevice,
    switchSpace = switchSpace,
    switchToFull = switchToFull,
)

/**
 * Full Space: the engineer sees the real room (passthrough), with the island floating in front
 * and the focused panel opening beside it, movable and resizable like the old panels.
 */
@Composable
fun SpatialIslandWorkspace(state: SessionState, actions: Actions, region: RegionHooks? = null, onRequestHomeSpace: () -> Unit) {
    // Full Space otherwise shows the system's virtual environment; this is AR, so ask for the
    // real room. On leaving, hand passthrough back to the system.
    val session = LocalSession.current
    DisposableEffect(session) {
        val environment = session?.scene?.spatialEnvironment
        environment?.preferredPassthroughOpacity = 1f
        onDispose { environment?.preferredPassthroughOpacity = SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE }
    }
    val ui = rememberIslandUi()
    val model = state.toIslandModel(demo = BuildConfig.TUNDRA_DEMO)
    val islandActions = islandActions(ui, actions, onRequestHomeSpace, switchToFull = false, region = region?.toggle)
    // The region frame lives in the room, not in the panel layout below.
    if (session != null && region != null) {
        RegionViewfinder(session, region.frame, state.region, onCapture = region.capture, onCancel = region.toggle)
    }
    Subspace {
        SpatialRow(horizontalArrangement = SpatialArrangement.spacedBy(32.dp)) {
            // The panel wraps the island and follows its size as it opens and closes.
            SpatialPanel(
                SubspaceModifier.offset(y = XrTokens.IslandLift).movable(),
                shape = SpatialRoundedCornerShape(CornerSize(XrTokens.IslandRadiusExpanded)),
            ) {
                RepairDynamicIsland(
                    model,
                    if (ui.expanded.value) PanelMode.Expanded else PanelMode.Compact,
                    islandActions,
                    photoFlash = state.photoFlash,
                )
            }
            val focused = ui.focused.value
            if (focused != null) {
                SpatialPanel(
                    SubspaceModifier.width(880.dp).height(760.dp).movable().resizable(minimumSize = FocusedMin, maximumSize = FocusedMax),
                    shape = SpatialRoundedCornerShape(CornerSize(XrTokens.FocusedPanelRadius)),
                ) {
                    FocusedPanel(focused, state, actions, onClose = ui::closeFocused)
                }
            }
        }
    }
}

/**
 * Home Space: one window. The island sits at the top centre; a focused panel fills the window
 * below it, with the island folded to its capsule meanwhile. Tapping the capsule closes the
 * focused panel and opens the island again.
 */
@Composable
fun HomeSpaceIslandWorkspace(state: SessionState, actions: Actions, canGoFullSpace: Boolean, onRequestFullSpace: () -> Unit) {
    val c = Tundra.colors
    val ui = rememberIslandUi()
    val model = state.toIslandModel(demo = BuildConfig.TUNDRA_DEMO)
    val base = islandActions(ui, actions, onRequestFullSpace.takeIf { canGoFullSpace }, switchToFull = true)
    val focused = ui.focused.value
    val islandActions = if (focused == null) base else IslandActions(
        expand = ui::closeFocused,
        collapse = base.collapse, askVoice = base.askVoice, photo = base.photo, record = base.record, region = base.region,
        screenshot = base.screenshot, upload = base.upload, chat = base.chat, voiceNote = base.voiceNote,
        manual = base.manual, draft = base.draft, send = base.send, ask = base.ask, source = base.source,
        openGuidance = base.openGuidance, openImage = base.openImage, openChat = base.openChat,
        openSession = base.openSession, selectDevice = base.selectDevice,
        switchSpace = base.switchSpace, switchToFull = base.switchToFull,
    )
    Box(Modifier.fillMaxSize().background(c.paper).safeDrawingPadding()) {
        DotPattern()
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.padding(16.dp)
                    .then(if (focused == null) Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()) else Modifier),
            ) {
                RepairDynamicIsland(
                    model,
                    if (ui.expanded.value && focused == null) PanelMode.Expanded else PanelMode.Compact,
                    islandActions,
                    photoFlash = state.photoFlash,
                )
            }
            if (focused != null) {
                FocusedPanel(
                    focused, state, actions, onClose = ui::closeFocused,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .clip(RoundedCornerShape(XrTokens.FocusedPanelRadius)),
                )
            }
        }
    }
}
