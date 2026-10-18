package com.tundra.tundraxr.ui.island

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tundra.tundraxr.R
import com.tundra.tundraxr.formatClock
import com.tundra.tundraxr.ui.components.IconCircle
import com.tundra.tundraxr.ui.theme.Tundra
import com.tundra.tundraxr.ui.theme.XrTokens

/** Room each labelled action needs; below six of these the labels give way to icons alone. */
private val LabelledActionWidth = 78.dp

/**
 * The six quick actions. Ghost icon buttons with a label under each, so nothing is guessed at.
 * In a narrow window the labels drop out (they stay as accessible names) rather than collide.
 */
@Composable
fun IslandActionBar(model: IslandModel, actions: IslandActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val labels = maxWidth >= LabelledActionWidth * 6
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CameraAction(model, actions, labels)
            Action(R.drawable.ic_scan, "Screenshot", actions.screenshot, labels, enabled = model.canScreenshot)
            Action(R.drawable.ic_image_up, "Upload", actions.upload, labels, enabled = model.canUpload)
            Action(R.drawable.ic_chat, "Chat", actions.chat, labels, agent = true)
            Action(R.drawable.ic_mic, "Voice", actions.voiceNote, labels, active = model.listeningForNote)
            Action(R.drawable.ic_manual, "Manual", actions.manual, labels)
        }
    }
}

/**
 * Photo or video from one slot. While a recording runs, the slot becomes its rust Stop button
 * with the running time, so it can always be ended from where it was started.
 */
@Composable
private fun CameraAction(model: IslandModel, actions: IslandActions, labels: Boolean) {
    val recordingSec = model.recordingSec
    if (recordingSec != null) {
        Action(R.drawable.ic_stop, formatClock(recordingSec), actions.record, labels, alert = true, description = "Stop recording")
        return
    }
    // Framing a region holds the slot the same way, so it is ended from where it began.
    if (model.region != null && actions.region != null) {
        Action(R.drawable.ic_close, "Cancel", actions.region, labels, active = true, description = "Cancel region capture")
        return
    }
    var choosing by remember { mutableStateOf(false) }
    Box {
        Action(R.drawable.ic_camera, "Camera", { choosing = true }, labels)
        val c = Tundra.colors
        DropdownMenu(choosing, onDismissRequest = { choosing = false }, containerColor = c.raised, modifier = Modifier.widthIn(min = 220.dp)) {
            DropdownMenuItem(
                text = { Text("Take photo", style = MaterialTheme.typography.bodyLarge, color = c.ink) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(22.dp), tint = c.inkMuted) },
                onClick = { choosing = false; actions.photo() },
            )
            DropdownMenuItem(
                text = { Text("Record video", style = MaterialTheme.typography.bodyLarge, color = c.ink) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_videocam), null, Modifier.size(22.dp), tint = c.inkMuted) },
                onClick = { choosing = false; actions.record() },
            )
            actions.region?.let { region ->
                DropdownMenuItem(
                    text = { Text("Frame a region", style = MaterialTheme.typography.bodyLarge, color = c.ink) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_scan), null, Modifier.size(22.dp), tint = c.inkMuted) },
                    onClick = { choosing = false; region() },
                )
            }
        }
    }
}

@Composable
private fun Action(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    showLabel: Boolean,
    active: Boolean = false,
    agent: Boolean = false,
    alert: Boolean = false,
    enabled: Boolean = true,
    description: String = label,
) {
    val c = Tundra.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconCircle(icon, description, onClick, selected = active, size = XrTokens.TargetPrimary, accent = agent, alert = alert, enabled = enabled)
        if (showLabel) Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                alert -> c.downFg
                active -> c.ink
                else -> c.inkMuted
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
