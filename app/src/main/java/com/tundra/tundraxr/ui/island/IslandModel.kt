package com.tundra.tundraxr.ui.island

import com.tundra.tundraxr.capture.RegionPhase
import com.tundra.tundraxr.data.ChatMessage
import com.tundra.tundraxr.data.Device
import com.tundra.tundraxr.data.ManualTarget
import com.tundra.tundraxr.data.Source
import com.tundra.tundraxr.data.SyncState
import com.tundra.tundraxr.formatClock

/** How much room the assistant takes in the engineer's view. UI state only. */
enum class PanelMode { Compact, Expanded, Focused }

/** What the agent is doing. Derived from session data; never stored on its own. */
enum class AiState(val label: String) {
    Idle("Ready"),
    Listening("Listening"),
    Thinking("Thinking"),
    Speaking("Answering"),
    Error("Needs attention"),
}

enum class CallStatus { Queued, Running, Ok, Failed }

/** One request to the brain, shown like the design system's `ToolCall`. */
data class CallProgress(
    /** The real endpoint, e.g. "POST /api/captures": a tool name is an API surface, not a label. */
    val endpoint: String,
    /** What it carries, e.g. "photo 3". */
    val subject: String,
    val status: CallStatus,
    val result: String? = null,
)

/**
 * The manual passage the latest answer cited. [excerpt] is null when the answer itself already
 * quotes it (the local brain's answers do), so the card doesn't repeat the bubble above it.
 */
data class Guidance(val page: Int, val section: String, val excerpt: String?, val target: ManualTarget)

/** The latest photo, or an uploaded image. [file] is a local path; null when the camera was off. */
data class ImageItem(val label: String, val file: String?, val sync: SyncState, val detail: String?)

/**
 * Everything the island shows, flattened from the session so the island's composables stay
 * pure and previewable. Built from SessionState in one place (step 4); nothing here calls the
 * brain.
 */
data class IslandModel(
    val ai: AiState = AiState.Idle,
    /** null until the first contact attempt with the laptop brain. */
    val online: Boolean? = null,
    val deviceName: String? = null,
    val assetTag: String? = null,
    val deviceId: String? = null,
    /** Devices the engineer can switch to when identification picked the wrong one. */
    val devices: List<Device> = emptyList(),
    val captureCount: Int = 0,
    /** Non-null while recording video. */
    val recordingSec: Int? = null,
    /** Non-null while framing a region with both hands. */
    val region: RegionPhase? = null,
    val progress: List<CallProgress> = emptyList(),
    val latest: ChatMessage? = null,
    val guidance: Guidance? = null,
    val image: ImageItem? = null,
    val suggestions: List<String> = emptyList(),
    val draft: String = "",
    val liveTranscript: String = "",
    val voiceError: String? = null,
    val listeningForAsk: Boolean = false,
    val listeningForNote: Boolean = false,
    /** Off until their flows exist (step 6); the buttons show disabled rather than doing nothing. */
    val canScreenshot: Boolean = false,
    val canUpload: Boolean = false,
    /** Answers and analysis are scripted (demo build); labelled so nobody takes them for records. */
    val demo: Boolean = false,
) {
    val activeCalls get() = progress.filter { it.status == CallStatus.Running || it.status == CallStatus.Queued }
}

/** The one card the expanded island shows under the latest message, most urgent first. */
sealed interface IslandCard {
    data class Progress(val calls: List<CallProgress>) : IslandCard
    data class Guide(val guidance: Guidance) : IslandCard
    data class Image(val image: ImageItem) : IslandCard
    data class Suggest(val suggestions: List<String>) : IslandCard
}

fun IslandModel.card(): IslandCard? = when {
    activeCalls.isNotEmpty() -> IslandCard.Progress(activeCalls)
    guidance != null -> IslandCard.Guide(guidance)
    image != null -> IslandCard.Image(image)
    latest == null && suggestions.isNotEmpty() -> IslandCard.Suggest(suggestions.take(3))
    else -> null
}

/** Tone of the compact status line. */
enum class StatusTone { Normal, Agent, Alert }

/** The single line the compact island has room for: text, an optional mono figure, a tone. */
data class StatusLine(val text: String, val figure: String? = null, val tone: StatusTone = StatusTone.Normal)

/** What matters most right now, in priority order. */
fun IslandModel.statusLine(): StatusLine = when {
    recordingSec != null -> StatusLine("Recording", formatClock(recordingSec), StatusTone.Alert)
    region == RegionPhase.Waiting -> StatusLine("Pinch with both hands to frame")
    region == RegionPhase.Framing -> StatusLine("Let go to set the frame")
    region == RegionPhase.Locked -> StatusLine("Capture, or pinch again to redraw")
    region == RegionPhase.Capturing -> StatusLine("Capturing region…", tone = StatusTone.Agent)
    listeningForAsk -> StatusLine(liveTranscript.ifEmpty { "Listening…" }, tone = StatusTone.Agent)
    listeningForNote -> StatusLine(liveTranscript.ifEmpty { "Voice note…" })
    ai == AiState.Error -> StatusLine(voiceError ?: "Something didn't go through", tone = StatusTone.Alert)
    ai == AiState.Thinking -> StatusLine("Thinking…", tone = StatusTone.Agent)
    ai == AiState.Speaking -> StatusLine("Answering…", tone = StatusTone.Agent)
    online == false -> StatusLine("Laptop offline", tone = StatusTone.Alert)
    activeCalls.isNotEmpty() -> {
        val first = activeCalls.first()
        val done = progress.size - activeCalls.size
        StatusLine(first.result ?: "Sending ${first.subject}", "$done/${progress.size}")
    }
    deviceName != null -> StatusLine(deviceName, assetTag)
    else -> StatusLine("Identifying device")
}

/**
 * Callbacks the island raises. Each maps onto an existing `Actions` entry in step 4; the
 * defaults let previews render without wiring.
 */
class IslandActions(
    val expand: () -> Unit = {},
    val collapse: () -> Unit = {},
    val askVoice: () -> Unit = {},
    val photo: () -> Unit = {},
    /** Starts recording, or stops it when one is running. */
    val record: () -> Unit = {},
    /** Starts two-hand region framing, or cancels it; null where it isn't possible (Home Space). */
    val region: (() -> Unit)? = null,
    val screenshot: () -> Unit = {},
    val upload: () -> Unit = {},
    val chat: () -> Unit = {},
    val voiceNote: () -> Unit = {},
    val manual: () -> Unit = {},
    val draft: (String) -> Unit = {},
    val send: () -> Unit = {},
    val ask: (String) -> Unit = {},
    val source: (Source) -> Unit = {},
    val openGuidance: (Guidance) -> Unit = {},
    val openImage: (ImageItem) -> Unit = {},
    val openChat: () -> Unit = {},
    val openSession: () -> Unit = {},
    val selectDevice: (Device) -> Unit = {},
    /** Moves between Home Space and Full Space; null where switching isn't possible. */
    val switchSpace: (() -> Unit)? = null,
    /** True when [switchSpace] goes to Full Space. */
    val switchToFull: Boolean = false,
)
