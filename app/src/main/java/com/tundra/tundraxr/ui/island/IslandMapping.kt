package com.tundra.tundraxr.ui.island

import com.tundra.tundraxr.SessionState
import com.tundra.tundraxr.VoiceTarget
import com.tundra.tundraxr.data.CaptureItem
import com.tundra.tundraxr.data.CaptureKind
import com.tundra.tundraxr.data.SyncState
import com.tundra.tundraxr.formatClock
import com.tundra.tundraxr.ui.ask.suggestionsFor

/**
 * What the agent is doing, read off the session. Requests keep running in the view model
 * whatever the island shows, so collapsing it never interrupts one.
 */
fun SessionState.aiState(): AiState {
    val reply = chat.lastOrNull()?.takeIf { !it.fromUser }
    return when {
        listeningFor == VoiceTarget.Ask -> AiState.Listening
        reply?.streaming == true && reply.text.isEmpty() -> AiState.Thinking
        reply?.streaming == true -> AiState.Speaking
        voiceError != null -> AiState.Error
        else -> AiState.Idle
    }
}

/** Everything the island shows, flattened from the session. The only place the two meet. */
fun SessionState.toIslandModel(demo: Boolean = false): IslandModel {
    val ai = aiState()
    val reply = chat.lastOrNull { !it.fromUser }
    val settledReply = reply?.takeUnless { it.streaming }
    val target = settledReply?.sources?.firstNotNullOfOrNull { it.manualTarget }
    // Chat messages and captures share the view model's id counter, so ids order them in time:
    // the card under the latest answer follows whichever happened last.
    val photo = captures.firstOrNull { it.kind == CaptureKind.Photo }
    val answerIsNewer = settledReply != null && (photo == null || settledReply.id > photo.id)

    val calls = captures.reversed().map { it.toCall() }.toMutableList()
    if (ai == AiState.Thinking) {
        val question = chat.lastOrNull { it.fromUser }?.text.orEmpty()
        calls += CallProgress("POST /api/ask", question.take(24), CallStatus.Running, "Searching the manual")
    }

    return IslandModel(
        ai = ai,
        online = brainOnline,
        deviceName = device?.name,
        assetTag = device?.assetTag,
        deviceId = device?.id,
        devices = devices,
        captureCount = captures.size,
        recordingSec = recordingSec.takeIf { isRecording },
        region = region,
        progress = calls,
        latest = reply,
        // The local brain's answer already quotes the passage, so the card names it and opens it.
        guidance = target?.takeIf { answerIsNewer }?.let { Guidance(it.page, it.reason, null, it) },
        image = photo?.takeUnless { answerIsNewer }?.let { ImageItem("Photo ${it.index}", it.file, it.sync, it.detail) },
        suggestions = suggestionsFor(device?.id),
        draft = askDraft,
        liveTranscript = liveTranscript,
        voiceError = voiceError,
        listeningForAsk = listeningFor == VoiceTarget.Ask,
        listeningForNote = listeningFor == VoiceTarget.Note,
        demo = demo,
    )
}

/** A capture as the request it is: uploading is the POST; analysis is followed by polling. */
private fun CaptureItem.toCall(): CallProgress {
    val subject = when (kind) {
        CaptureKind.Photo -> "photo $index"
        CaptureKind.Video -> "video ${formatClock(durationSec)}"
        CaptureKind.Note -> "note"
    }
    return when (sync) {
        SyncState.Sending -> CallProgress("POST /api/captures", subject, CallStatus.Running, "Uploading")
        SyncState.Analyzing -> CallProgress("GET /api/captures", subject, CallStatus.Running, detail ?: "Analyzing on the laptop")
        SyncState.InBrain, SyncState.Analyzed -> CallProgress("POST /api/captures", subject, CallStatus.Ok, detail ?: "In brain")
        SyncState.Failed -> CallProgress("POST /api/captures", subject, CallStatus.Failed, detail ?: "Not sent")
    }
}
