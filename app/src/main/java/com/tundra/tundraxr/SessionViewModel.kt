package com.tundra.tundraxr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tundra.tundraxr.capture.RegionPhase
import com.tundra.tundraxr.data.CaptureItem
import com.tundra.tundraxr.data.CaptureKind
import com.tundra.tundraxr.data.ChatMessage
import com.tundra.tundraxr.data.DemoBrain
import com.tundra.tundraxr.data.Device
import com.tundra.tundraxr.data.LocalBrain
import com.tundra.tundraxr.data.Manual
import com.tundra.tundraxr.data.ManualTarget
import com.tundra.tundraxr.data.SyncState
import com.tundra.tundraxr.data.TundraBrain
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Where speech goes: into the device record, or to the brain as a question. */
enum class VoiceTarget { Note, Ask }

data class SessionState(
    val device: Device? = null,
    val devices: List<Device> = emptyList(),
    val manual: Manual? = null,
    /** Null until the first contact attempt with the laptop brain. */
    val brainOnline: Boolean? = null,
    val cameraReady: Boolean = false,
    val isRecording: Boolean = false,
    val recordingSec: Int = 0,
    val captures: List<CaptureItem> = emptyList(),
    val listeningFor: VoiceTarget? = null,
    val liveTranscript: String = "",
    /** Shown briefly when voice input fails, so it never fails silently. */
    val voiceError: String? = null,
    val chat: List<ChatMessage> = emptyList(),
    val askDraft: String = "",
    val showAsk: Boolean = true,
    val showManual: Boolean = false,
    val manualTarget: ManualTarget? = null,
    val photoFlash: Long = 0L,
    /** Two-hand region capture; null when it's off. */
    val region: RegionPhase? = null,
) {
    val photoCount get() = captures.count { it.kind == CaptureKind.Photo }
    val syncedCount get() = captures.count { it.sync != SyncState.Sending && it.sync != SyncState.Failed }
}

class SessionViewModel(
    // Demo builds (tundra.demo=true) answer from the scripted brain so the UI can be tested offline.
    private val brain: TundraBrain = if (BuildConfig.TUNDRA_DEMO) DemoBrain() else LocalBrain(BuildConfig.TUNDRA_SERVER_URL),
) : ViewModel() {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val sessionStart = System.currentTimeMillis()
    private val sessionId = "s_$sessionStart"
    private var nextId = 1L
    private var recordingJob: Job? = null

    init {
        viewModelScope.launch {
            val device = brain.identifyDevice()
            _state.update { it.copy(device = device) }
            _state.update { it.copy(devices = brain.devices()) }
            loadManual(device)
        }
        viewModelScope.launch { pollBrain() }
    }

    /** Identification is simulated, so the engineer can correct it. */
    fun selectDevice(device: Device) {
        if (device.id == _state.value.device?.id) return
        _state.update { it.copy(device = device, manual = null, manualTarget = null, chat = emptyList()) }
        viewModelScope.launch { loadManual(device) }
    }

    private suspend fun loadManual(device: Device) {
        val manual = brain.manual(device.manualId)
        _state.update { if (it.device?.id == device.id) it.copy(manual = manual) else it }
    }

    fun onCameraReady(ready: Boolean) = _state.update { it.copy(cameraReady = ready) }

    // ── Capture ────────────────────────────────────────────────────────────

    fun onRecordingStarted() {
        _state.update { it.copy(isRecording = true, recordingSec = 0) }
        recordingJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _state.update { it.copy(recordingSec = it.recordingSec + 1) }
            }
        }
    }

    fun onRecordingStopped(file: File?) {
        recordingJob?.cancel()
        val seconds = _state.value.recordingSec
        _state.update { it.copy(isRecording = false, recordingSec = 0) }
        add(CaptureItem(nextId++, CaptureKind.Video, stamp(), file = file?.path, durationSec = seconds.coerceAtLeast(1)))
    }

    fun onRegionPhase(phase: RegionPhase?) = _state.update { it.copy(region = phase) }

    fun onShutter() = _state.update { it.copy(photoFlash = System.currentTimeMillis()) }

    fun onPhotoTaken(file: File?) {
        add(CaptureItem(nextId++, CaptureKind.Photo, stamp(), file = file?.path, index = _state.value.photoCount + 1))
    }

    // ── Voice ──────────────────────────────────────────────────────────────

    fun onListeningStarted(target: VoiceTarget) =
        _state.update { it.copy(listeningFor = target, liveTranscript = "", voiceError = null) }

    fun onPartialTranscript(text: String) = _state.update { it.copy(liveTranscript = text) }

    fun onFinalTranscript(text: String) {
        val target = _state.value.listeningFor
        _state.update { it.copy(listeningFor = null, liveTranscript = "") }
        if (text.isBlank()) return
        when (target) {
            VoiceTarget.Note -> add(CaptureItem(nextId++, CaptureKind.Note, stamp(), transcript = text.trim()))
            VoiceTarget.Ask -> ask(text)
            null -> Unit
        }
    }

    fun onListeningCancelled() = _state.update { it.copy(listeningFor = null, liveTranscript = "") }

    private var voiceErrorJob: Job? = null

    fun onVoiceError(message: String) = showError(message)

    /** A short notice in the island's status line, cleared after a few seconds. */
    fun showError(message: String) {
        _state.update { it.copy(voiceError = message) }
        voiceErrorJob?.cancel()
        voiceErrorJob = viewModelScope.launch {
            delay(4000)
            _state.update { it.copy(voiceError = null) }
        }
    }

    // ── Ask ────────────────────────────────────────────────────────────────

    fun onAskDraftChange(text: String) = _state.update { it.copy(askDraft = text) }

    fun ask(question: String = _state.value.askDraft) {
        val device = _state.value.device ?: return
        if (question.isBlank()) return
        val user = ChatMessage(nextId++, fromUser = true, text = question.trim())
        val replyId = nextId++
        _state.update {
            it.copy(
                askDraft = "",
                showAsk = true,
                chat = it.chat + user + ChatMessage(replyId, fromUser = false, text = "", streaming = true),
            )
        }
        viewModelScope.launch {
            val answer = brain.ask(sessionId, device, question, _state.value.captures)
            // Stream word by word so the answer reads in as it's "thought".
            val words = answer.text.split(" ")
            for (i in words.indices) {
                val partial = words.take(i + 1).joinToString(" ")
                updateMessage(replyId) { it.copy(text = partial) }
                delay(28)
            }
            updateMessage(replyId) { it.copy(streaming = false, sources = answer.sources) }
            // If the brain cites the manual, bring it up at that passage.
            answer.sources.firstNotNullOfOrNull { it.manualTarget }?.let(::openManual)
        }
    }

    // ── Panels ─────────────────────────────────────────────────────────────

    fun openManual(target: ManualTarget? = _state.value.manualTarget) =
        _state.update { it.copy(showManual = true, manualTarget = target) }

    fun toggleManual() = _state.update { it.copy(showManual = !it.showManual) }
    fun toggleAsk() = _state.update { it.copy(showAsk = !it.showAsk) }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun add(item: CaptureItem) {
        val device = _state.value.device ?: return
        _state.update { it.copy(captures = listOf(item) + it.captures) }
        viewModelScope.launch {
            val result = runCatching { brain.ingest(sessionId, device, item) }
            updateCapture(item.id) { c ->
                result.fold(
                    onSuccess = { (remoteId, status) -> c.copy(remoteId = remoteId, sync = status.sync, detail = status.detail) },
                    onFailure = { c.copy(sync = SyncState.Failed, detail = "Not sent · laptop offline") },
                )
            }
            if (result.isFailure) _state.update { it.copy(brainOnline = false) }
        }
    }

    /** Heartbeat + analysis status: TwelveLabs results arrive on the laptop, and their status shows here. */
    private suspend fun pollBrain() {
        while (true) {
            val statuses = brain.statuses(sessionId)
            _state.update { s ->
                s.copy(
                    brainOnline = statuses != null,
                    captures = if (statuses == null) s.captures else s.captures.map { c ->
                        val remote = c.remoteId?.let(statuses::get)
                        if (remote == null || c.sync == SyncState.Sending) c else c.copy(sync = remote.sync, detail = remote.detail)
                    },
                )
            }
            delay(3000)
        }
    }

    private fun updateCapture(id: Long, transform: (CaptureItem) -> CaptureItem) =
        _state.update { s -> s.copy(captures = s.captures.map { if (it.id == id) transform(it) else it }) }

    private fun updateMessage(id: Long, transform: (ChatMessage) -> ChatMessage) =
        _state.update { s -> s.copy(chat = s.chat.map { if (it.id == id) transform(it) else it }) }

    private fun stamp(): String {
        val sec = ((System.currentTimeMillis() - sessionStart) / 1000).toInt()
        return formatClock(sec)
    }
}

fun formatClock(totalSec: Int): String = "%02d:%02d".format(totalSec / 60, totalSec % 60)
