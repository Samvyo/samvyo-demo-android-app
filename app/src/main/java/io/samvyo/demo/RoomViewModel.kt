package io.samvyo.demo

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.samvyo.sdk.JoinState
import io.samvyo.sdk.Participant
import io.samvyo.sdk.RoomConstants
import io.samvyo.sdk.RoomEvent
import io.samvyo.sdk.VideoRoom
import io.samvyo.sdk.VideoSdk
import io.samvyo.sdk.screen.ScreenCaptureService
import io.samvyo.sdk.signalling.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.EglBase
import org.webrtc.VideoTrack

class RoomViewModel(application: Application) : AndroidViewModel(application) {

    // ── Screen state ───────────────────────────────────────────────────────────

    private val _ui = MutableStateFlow<RoomUiState>(RoomUiState.Idle)
    val ui: StateFlow<RoomUiState> = _ui.asStateFlow()

    // ── Debug log (shown on the join screen) ──────────────────────────────────

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    // ── One-shot notification banner (mic forced off, room closed reason, etc.) ─

    private val _notification = MutableStateFlow<String?>(null)
    val notification: StateFlow<String?> = _notification.asStateFlow()

    // ── Video tracks ───────────────────────────────────────────────────────────

    private val _localVideoTrack   = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    private val _remoteScreenTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteScreenTracks: StateFlow<Map<String, VideoTrack>> = _remoteScreenTracks.asStateFlow()

    // ── EGL context (for VideoRenderer initialisation) ─────────────────────────

    private val _eglContext = MutableStateFlow<EglBase.Context?>(null)
    val eglContext: StateFlow<EglBase.Context?> = _eglContext.asStateFlow()

    // ── Participants ───────────────────────────────────────────────────────────

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

    // ── Per-peer mute state (audio + video) ────────────────────────────────────

    private val _peerMuteStates = MutableStateFlow<Map<String, PeerMuteState>>(emptyMap())
    val peerMuteStates: StateFlow<Map<String, PeerMuteState>> = _peerMuteStates.asStateFlow()

    // ── Active speaker ─────────────────────────────────────────────────────────

    private val _activeSpeakerId = MutableStateFlow<String?>(null)
    val activeSpeakerId: StateFlow<String?> = _activeSpeakerId.asStateFlow()

    // ── Room lock (moderator-controlled) ────────────────────────────────────────

    private val _isRoomLocked = MutableStateFlow(false)
    val isRoomLocked: StateFlow<Boolean> = _isRoomLocked.asStateFlow()

    // ── Transient socket reconnect (wifi blip etc. during an active call) ───────
    // Set while ConnectionState.Failed fires mid-call and SignallingClient is retrying
    // with its own exponential backoff; cleared on ConnectionState.Reconnected. Does NOT
    // flip [_ui] to Error — see the connectionState.collect block in observeRoom() below.

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    // ── Viewer count (EVENT-type rooms only) ─────────────────────────────────────
    // Stays null until the server actually sends one — it only does so for EVENT
    // rooms, so null-vs-populated doubles as the "is this even an event room" signal
    // without RoomViewModel needing to separately track the roomType it was given.

    private val _viewerCount = MutableStateFlow<Int?>(null)
    val viewerCount: StateFlow<Int?> = _viewerCount.asStateFlow()

    // ── P2P mode signals (ROOM_P2P rooms only) ───────────────────────────────────
    // Both only ever populate when the room actually runs in P2P mode — the SDK
    // never emits RoomModeChanging/Changed or PeerConnectionFailed for SFU rooms.

    // True from RoomModeChanging until RoomModeChanged — the P2P→SFU auto-migration
    // window (server-driven once the room crosses its p2pThreshold peer count).
    private val _isMigratingToSfu = MutableStateFlow(false)
    val isMigratingToSfu: StateFlow<Boolean> = _isMigratingToSfu.asStateFlow()

    // Peers whose direct P2P connection hard-failed (ICE "failed", not "disconnected" —
    // see PeerConnectionFailed's doc comment). Cleared on PeerConnected for that peer.
    private val _peerConnectionFailed = MutableStateFlow<Set<String>>(emptySet())
    val peerConnectionFailed: StateFlow<Set<String>> = _peerConnectionFailed.asStateFlow()

    // ── Local media controls ───────────────────────────────────────────────────

    private val _isMicMuted    = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isCameraOff   = MutableStateFlow(false)
    val isCameraOff: StateFlow<Boolean> = _isCameraOff.asStateFlow()

    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing: StateFlow<Boolean> = _isScreenSharing.asStateFlow()

    private val _isRecording   = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _handRaised    = MutableStateFlow(false)
    val handRaised: StateFlow<Boolean> = _handRaised.asStateFlow()

    private val _isLiveStreaming = MutableStateFlow(false)
    val isLiveStreaming: StateFlow<Boolean> = _isLiveStreaming.asStateFlow()

    private val _transcriptionActive = MutableStateFlow(false)
    val transcriptionActive: StateFlow<Boolean> = _transcriptionActive.asStateFlow()

    private val _transcriptionEntries = MutableStateFlow<List<TranscriptionEntry>>(emptyList())
    val transcriptionEntries: StateFlow<List<TranscriptionEntry>> = _transcriptionEntries.asStateFlow()

    // ── Chat ───────────────────────────────────────────────────────────────────

    private val _chatMessages  = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    // ── Hand raise list (other peers, for the moderator's panel) ───────────────

    private val _handRaiseList = MutableStateFlow<List<HandRaiseEntry>>(emptyList())
    val handRaiseList: StateFlow<List<HandRaiseEntry>> = _handRaiseList.asStateFlow()

    // ── Emoji reactions (last 6 shown in the corner) ────────────────────────────

    private val _recentReactions = MutableStateFlow<List<EmojiReaction>>(emptyList())
    val recentReactions: StateFlow<List<EmojiReaction>> = _recentReactions.asStateFlow()

    // ── Private chat target ─────────────────────────────────────────────────────
    // null = "Everyone" (public chat). Set via setPrivateChatTarget() from the chat
    // panel's "Change" picker, mirrors the RN app's selectedChatReceiver state.

    private val _selectedChatReceiver = MutableStateFlow<String?>(null)
    val selectedChatReceiver: StateFlow<String?> = _selectedChatReceiver.asStateFlow()

    // ── Upgrade-request flow ─────────────────────────────────────────────────────
    // Two independent pieces of state for the two ends of the same wire message
    // (VideoRoom.onModUpgradeReq() — see UpgradeRequest's doc comment):
    //   _pendingUpgradeRequests — moderator side: invites we've sent, awaiting a reply.
    //   _incomingUpgradeInvite  — peer side: the one invite a moderator sent *us*, if any.

    private val _pendingUpgradeRequests = MutableStateFlow<List<UpgradeRequest>>(emptyList())
    val pendingUpgradeRequests: StateFlow<List<UpgradeRequest>> = _pendingUpgradeRequests.asStateFlow()

    private val _incomingUpgradeInvite = MutableStateFlow<UpgradeRequest?>(null)
    val incomingUpgradeInvite: StateFlow<UpgradeRequest?> = _incomingUpgradeInvite.asStateFlow()

    // ── Waiting room (password-protected rooms) ─────────────────────────────────

    private val _waitingPeers = MutableStateFlow<List<WaitingPeer>>(emptyList())
    val waitingPeers: StateFlow<List<WaitingPeer>> = _waitingPeers.asStateFlow()

    // ── Local peer identity ────────────────────────────────────────────────────
    // We store the role the local user chose before joining (Moderator / Participant / Viewer).
    // The call screen reads this to decide which buttons to show.
    // Example: "Start REC" is only visible when localPeerType == PEER_MODERATOR.
    // This is set in joinRoom() and cleared in resetCallState() when the call ends.

    private val _localPeerType = MutableStateFlow<String?>(null)
    val localPeerType: StateFlow<String?> = _localPeerType.asStateFlow()

    // ── AI Agent ────────────────────────────────────────────────────────────────
    // Four independent pieces — see SDK_UPDATE_FIXES.md's 2026-08-24 entry for the full
    // design rationale. State here mirrors what VideoRoom itself already tracks
    // (getPendingAiConsent()/getAiMyConsent()/getAiObservations()) instead of being
    // re-derived locally, so a late-mounting UI or a server-driven expiry can never
    // drift out of sync with what the server actually decided.

    // Moderator's stream picker — one row per (peerId, mediaTag) the server says can be
    // added right now. startAiAgent() only accepts a list of PEER ids, not per-mediaTag
    // targets, so ticks are tracked per peerId even though the picker's rows are per-stream.
    private val _aiSelectableStreams = MutableStateFlow<List<RoomEvent.AiSelectableStream>>(emptyList())
    val aiSelectableStreams: StateFlow<List<RoomEvent.AiSelectableStream>> = _aiSelectableStreams.asStateFlow()

    private val _aiTickedPeers = MutableStateFlow<Set<String>>(emptySet())
    val aiTickedPeers: StateFlow<Set<String>> = _aiTickedPeers.asStateFlow()

    private val _isAiAgentActive = MutableStateFlow(false)
    val isAiAgentActive: StateFlow<Boolean> = _isAiAgentActive.asStateFlow()

    // The one pending consent request for THIS device, if any. Kept in lockstep with
    // VideoRoom.getPendingAiConsent() — the SDK narrows/clears its own copy internally
    // on aiConsentExpired, so we mirror it rather than computing expiry ourselves.
    private val _pendingAiConsent = MutableStateFlow<RoomEvent.AiConsentRequest?>(null)
    val pendingAiConsent: StateFlow<RoomEvent.AiConsentRequest?> = _pendingAiConsent.asStateFlow()

    private val _aiConsentError = MutableStateFlow<String?>(null)
    val aiConsentError: StateFlow<String?> = _aiConsentError.asStateFlow()

    // This device's own streams' AI consent state — independent of the moderator's picker.
    private val _myAiStreams = MutableStateFlow<List<RoomEvent.AiMyConsentStream>>(emptyList())
    val myAiStreams: StateFlow<List<RoomEvent.AiMyConsentStream>> = _myAiStreams.asStateFlow()

    private val _aiObservations = MutableStateFlow<List<RoomEvent.AiAnalysis>>(emptyList())
    val aiObservations: StateFlow<List<RoomEvent.AiAnalysis>> = _aiObservations.asStateFlow()

    // ── Session ────────────────────────────────────────────────────────────────

    private var room: VideoRoom? = null
    private var isFrontCamera = true

    // =========================================================================
    // Room lifecycle
    // =========================================================================

    /**
     * Step 1: fetch token + open WebSocket.
     * [roomType] = RoomConstants.ROOM_CONFERENCING or ROOM_EVENT.
     */
    fun initRoom(
        roomId: String,
        peerName: String,
        roomType: String = RoomConstants.ROOM_CONFERENCING,
    ) {
        if (roomId.isBlank()) {
            _ui.value = RoomUiState.Error("Room ID cannot be empty")
            return
        }
        _ui.value = RoomUiState.Connecting
        appendLog("Fetching session token…")

        viewModelScope.launch {
            // No ACCESS_KEY/SECRET_ACCESS_KEY in the app — they'd be extractable from the APK.
            // Instead we ask a customer-run token server (holds the keys server-side, same
            // react-native-demo-app-server pattern as the RN reference app) for a session token,
            // sending only the roomId.
            val token = fetchSessionTokenFromServer(roomId).getOrElse {
                _ui.value = RoomUiState.Error(it.message ?: "Token fetch failed")
                appendLog("✗ ${it.message}")
                return@launch
            }
            appendLog("Token OK — opening WebSocket…")

            val newRoom = try {
                VideoSdk.init(
                    context      = getApplication(),
                    sessionToken = token,
                    roomId       = roomId,
                    peerName     = peerName,
                    roomType     = roomType,
                )
            } catch (e: Exception) {
                _ui.value = RoomUiState.Error(e.message ?: "Init failed")
                appendLog("✗ ${e.message}")
                return@launch
            }

            room = newRoom
            _eglContext.value = newRoom.eglBaseContext
            observeRoom(newRoom)
        }
    }

    /**
     * Step 2: join the room with a chosen role.
     * [peerType]            = PEER_PARTICIPANT | PEER_MODERATOR | PEER_VIEWER
     * [produce]             = false for viewers who should never send media
     * [consume]             = false for send-only peers (rare)
     * [enableScreenSharing] = must be true at join time to use enableShare() later
     */
    fun joinRoom(
        peerType: String  = RoomConstants.PEER_PARTICIPANT,
        produce: Boolean  = true,
        consume: Boolean  = true,
        enableScreenSharing: Boolean = true,
    ) {
        val r = room ?: return
        _ui.value = RoomUiState.Joining
        // Save the role the user picked so the call screen can gate moderator-only buttons.
        // Without this, every user would see "Start REC", "Kick", "Upgrade", etc.
        _localPeerType.value = peerType
        appendLog("⇢ joinRoom peerType=$peerType produce=$produce consume=$consume")
        r.joinRoom(
            peerType            = peerType,
            produce             = produce,
            consume             = consume,
            enableScreenSharing = enableScreenSharing,
            enableChatOption    = true,
        )
    }

    /** Leave the room (only we leave; others stay). */
    fun leaveRoom() {
        room?.leaveRoom()
        stopScreenShareService()
        resetCallState()
        appendLog("⇢ left room")
    }

    /** End the room for everyone (moderator only). */
    fun closeRoom() {
        room?.closeRoom()
        stopScreenShareService()
        resetCallState()
        appendLog("⇢ room closed by moderator")
    }

    // =========================================================================
    // Media controls
    // =========================================================================

    fun toggleMic() {
        val newMuted = !_isMicMuted.value
        _isMicMuted.value = newMuted
        room?.setMicEnabled(!newMuted)
        appendLog(if (newMuted) "• mic muted" else "• mic unmuted")
    }

    /**
     * Uses the hard `disableCam()`/`enableCam()` pair, not the soft `setCameraEnabled()`
     * pause/resume — matches the fix just applied on the iOS demo app (and RN's own
     * toggleCamera(), which never had a soft video toggle to begin with). Confirmed live
     * against the server there: a soft pause still closes the peer's consumer server-side
     * (consumerClosed, reason producerclose), but a soft resume never creates a new
     * producer to re-trigger a fresh consumer push, so the other peer's video never came
     * back. The hard pair sends closeProducerSDK first, so the server knows the old
     * producer is truly gone, then produces a genuinely new one on re-enable, which does
     * trigger a fresh consumer for peers.
     * `_isCameraOff` isn't flipped here — it's synced from the resulting local
     * VideoStart/VideoEnd events instead (see handleEvent()).
     */
    fun toggleCamera() {
        if (_isCameraOff.value) {
            room?.enableCam()
            appendLog("⇢ enableCam")
        } else {
            room?.disableCam()
            appendLog("⇢ disableCam")
        }
    }

    fun flipCamera() {
        val enumerator = Camera2Enumerator(getApplication())
        val deviceNames = enumerator.deviceNames
        val targetCamera = if (isFrontCamera) {
            deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        } else {
            deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        }
        if (targetCamera != null) {
            room?.changeVideoInput(targetCamera)
            isFrontCamera = !isFrontCamera
            appendLog("• flipped camera → ${if (isFrontCamera) "front" else "back"}")
        }
    }

    /**
     * Full camera list for the "Select Devices" picker (video half only — see
     * VideoDevice's doc comment for why there's no audio equivalent). Camera2Enumerator
     * only reports front/back on nearly every phone, but some devices (foldables,
     * multi-lens setups) expose more, so we list everything rather than assume two.
     */
    fun listVideoDevices(): List<VideoDevice> {
        val enumerator = Camera2Enumerator(getApplication())
        return enumerator.deviceNames.map { name ->
            VideoDevice(
                id       = name,
                label    = if (enumerator.isFrontFacing(name)) "Front Camera" else "Back Camera",
                isFront  = enumerator.isFrontFacing(name),
            )
        }
    }

    /** User picked a specific camera from the device list, not just flip-toggling. */
    fun selectVideoDevice(device: VideoDevice) {
        room?.changeVideoInput(device.id)
        isFrontCamera = device.isFront
        appendLog("• selected camera → ${device.label}")
    }

    // A camera chosen on the *join screen*, before a room/MediaEngine exists yet.
    // VideoRoom.changeVideoInput() is a no-op until the camera capturer has been created
    // (see MediaEngine.kt:361 — `cameraCapturer ?: return`), so we can't apply this the
    // moment it's picked; we stash it and apply it once local video actually starts
    // (see the VideoStart "local" branch in handleEvent() below).
    private var preferredCamera: VideoDevice? = null

    /** Called from the join screen's "Select Camera" picker, before joining. */
    fun setPreferredCamera(device: VideoDevice?) {
        preferredCamera = device
    }

    // =========================================================================
    // Screen sharing
    // =========================================================================

    /**
     * Called after the user grants MediaProjection permission.
     * [resultCode] and [projectionData] come from the system "Start recording?" dialog result.
     * The ScreenCaptureService must be started before calling room.enableShare().
     */
    fun startScreenShare(resultCode: Int, projectionData: Intent) {
        val serviceIntent = Intent(getApplication(), ScreenCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(serviceIntent)
        } else {
            getApplication<Application>().startService(serviceIntent)
        }
        room?.enableShare(resultCode, projectionData)
        appendLog("⇢ screen share started")
    }

    fun stopScreenShare() {
        room?.disableShare()
        stopScreenShareService()
        appendLog("⇢ screen share stopped")
    }

    private fun stopScreenShareService() {
        getApplication<Application>().stopService(
            Intent(getApplication(), ScreenCaptureService::class.java)
        )
    }

    // =========================================================================
    // Recording
    // =========================================================================

    fun toggleRecording() {
        if (_isRecording.value) {
            room?.stopRecording()
            appendLog("⇢ stopRecording")
        } else {
            room?.startRecording()
            appendLog("⇢ startRecording")
        }
    }

    // =========================================================================
    // Live streaming
    // =========================================================================
    // Entirely server-driven, like recording — the phone's only job is to ask, then
    // report what happened. VideoRoom.startLiveStreaming() concatenates url+key into
    // one "rtmpUrl" wire field itself (a documented RN-parity gotcha), so we just pass
    // the two raw values the UI collected.

    fun startLiveStream(url: String, key: String) {
        room?.startLiveStreaming(url, key)
        appendLog("⇢ startLiveStreaming")
    }

    fun stopLiveStream() {
        room?.stopLiveStreaming()
        appendLog("⇢ stopLiveStreaming")
    }

    // =========================================================================
    // Transcription
    // =========================================================================
    // Unlike recording/live-streaming, start/stop here are LOCAL flag flips — VideoRoom.
    // startTranscription()/stopTranscription() emit TranscriptionStarted/Stopped
    // synchronously with no server round-trip; the server independently decides whether
    // to actually run speech-to-text on this room's already-flowing audio.

    fun startTranscription() {
        room?.startTranscription()
        appendLog("⇢ startTranscription")
    }

    fun stopTranscription() {
        room?.stopTranscription()
        appendLog("⇢ stopTranscription")
    }

    // =========================================================================
    // Hand raise
    // =========================================================================

    fun toggleHand() {
        if (_handRaised.value) {
            room?.dropHand()
            appendLog("⇢ hand dropped")
        } else {
            room?.raiseHand()
            appendLog("⇢ hand raised")
        }
    }

    /** Moderator lowers another peer's raised hand. */
    fun lowerPeerHand(peerId: String) {
        room?.dropHand(targetPeerId = peerId, moderator = true)
        appendLog("⇢ lowered hand for ${peerId.take(8)}")
    }

    // =========================================================================
    // Chat
    // =========================================================================

    /** Set who the next chat message(s) go to. null = everyone (public). */
    fun setPrivateChatTarget(peerId: String?) {
        _selectedChatReceiver.value = peerId
    }

    fun sendChat(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val target = _selectedChatReceiver.value
        val scope = if (target != null) RoomConstants.MSG_TYPE_PRIVATE else RoomConstants.MSG_TYPE_PUBLIC
        room?.sendCustomMessage(
            data           = trimmed,
            // `type` identifies the message KIND ("chat"), `messageType` carries the
            // public/private SCOPE — confirmed by reading both the RN reference app's
            // sendCustomMessage(text, 'chat', receiverPeerId, senderType, scope) call
            // and the Flutter demo app's identical convention directly. This was
            // previously inverted here (type=scope, messageType="chat"), which meant a
            // chat message sent by this app was never recognized as chat by RN/Flutter's
            // own `message.type === 'chat'` check on receipt, and this app's own receive
            // side (`event.messageType == "chat"`) never matched an RN/Flutter sender's
            // `messageType: scope` either — chat was silently broken in both directions
            // cross-platform. See SDK_UPDATE_FIXES.md's 2026-08-24 entry.
            type           = "chat",
            receiverPeerId = target,
            messageType    = scope,
        )
        _chatMessages.value = _chatMessages.value + ChatMessage(
            senderPeerId = room?.peerId ?: "",
            senderName   = room?.peerName ?: "Me",
            text         = trimmed,
            isLocal      = true,
            scope        = scope,
        )
        appendLog("⇢ chat${if (target != null) " (private to ${target.take(8)})" else ""}: $trimmed")
    }

    // =========================================================================
    // Emoji reactions
    // =========================================================================
    // Mirrors the RN reference app's sendReaction(): reactions ride the same generic
    // sendCustomMessage envelope as chat, distinguished only by messageType="emoji"
    // (RN sends messageType="custom" with a JSON {type:"emoji-reaction"} payload instead —
    // we use a dedicated messageType string here since our data payload is just the emoji
    // itself, no JSON parsing needed on receipt).

    /**
     * Cross-platform reality check (confirmed against the actual RN/Flutter/iOS demo apps,
     * not assumed): none of them use `messageType` to identify a reaction at all — the
     * marker lives *inside* the JSON-encoded `data` payload as `{"type":"emoji-reaction",
     * "emoji":...}`. RN JSON-stringifies that object before calling sendCustomMessage, so
     * its wire `data` is a STRING; Flutter passes the raw object through unstringified, so
     * its wire `data` is a nested OBJECT — the two don't even agree with each other (Flutter's
     * own receiver requires `data is Map` and silently drops RN's string form; iOS's parser
     * requires `data` to be a string and silently drops Flutter's object form). Our SDK's
     * sendCustomMessage(data: String, ...) forces us into the RN/iOS shape (a string) — we
     * can't send Flutter's raw-object form even if we wanted to — so this app's own reactions
     * will interop with RN and iOS but not Flutter (that's Flutter's own receiver bug, not
     * fixable from here). See handleEvent()'s CustomMessage branch for the matching receive
     * side, which — unlike sending — is made robust to BOTH shapes.
     */
    fun sendReaction(emoji: String) {
        room?.sendCustomMessage(
            data = JSONObject().apply {
                put("type", "emoji-reaction")
                put("emoji", emoji)
            }.toString(),
            type = "custom",
        )
        // Optimistic local echo — the server doesn't reflect our own CustomMessage back to us,
        // so we append it here exactly like sendChat() does for the local chat bubble.
        _recentReactions.value = (_recentReactions.value + EmojiReaction(
            emoji    = emoji,
            fromName = "You",
        )).takeLast(6)
        appendLog("⇢ reaction: $emoji")
    }

    // =========================================================================
    // Moderator actions
    // =========================================================================

    fun kickPeer(peerId: String) {
        room?.logThisUserOutOfMeeting(peerId)
        appendLog("⇢ kicked $peerId")
    }

    fun upgradePeer(peerId: String) {
        room?.upgradeParticipant(peerId, audioStatus = true, videoStatus = false)
        appendLog("⇢ upgraded $peerId")
    }

    fun downgradePeer(peerId: String) {
        room?.downgradeParticipant(peerId)
        appendLog("⇢ downgraded $peerId")
    }

    /**
     * Force-mutes every other participant's mic in one call — added to the SDK
     * in this same pass (see SDK_UPDATE_FIXES.md's 2026-08-24 entry); previously
     * documented here as a known gap with no per-peer workaround worth building.
     */
    fun muteAllParticipants() {
        room?.muteAllParticipants()
        appendLog("⇢ muteAllParticipants")
    }

    // =========================================================================
    // Upgrade requests
    // =========================================================================
    // Two flows share this section:
    //  1. Viewer-initiated: requestUpgrade() sends a handRaise(upgradeRequest=true), which
    //     the moderator already sees in the Hands Raised panel (Phase 2B) — no separate
    //     tracking needed here, that flow is complete via upgradePeer() from that panel.
    //  2. Moderator-initiated: sendUpgradeInvite() invites a specific peer; they see an
    //     Accept/Decline banner (_incomingUpgradeInvite) and respond via acceptUpgrade()/
    //     rejectUpgrade(); the moderator tracks the outstanding invite in
    //     _pendingUpgradeRequests until UpgradeRequestRejected/ParticipantUpgraded arrives.

    /** Viewer/attendee asks to be made a presenter. */
    fun requestUpgrade() {
        room?.requestUpgradeToPresenter()
        appendLog("⇢ requested upgrade to presenter")
    }

    /** We accept a moderator's invite to become a presenter. */
    fun acceptUpgrade() {
        room?.acceptUpgradeRequest(audioStatus = true, videoStatus = false)
        _incomingUpgradeInvite.value = null
        appendLog("⇢ accepted upgrade invite")
    }

    /** We decline a moderator's invite to become a presenter. */
    fun rejectUpgrade() {
        val moderatorPeerId = _incomingUpgradeInvite.value?.moderatorPeerId ?: return
        room?.rejectUpgradeRequest(moderatorPeerId)
        _incomingUpgradeInvite.value = null
        appendLog("⇢ declined upgrade invite")
    }

    /** Moderator invites a specific peer to become a presenter. */
    fun sendUpgradeInvite(peerId: String) {
        room?.sendUpgradeRequest(peerId, status = true)
        appendLog("⇢ sent upgrade invite to $peerId")
    }

    /** Moderator withdraws an invite that hasn't been responded to yet. */
    fun cancelUpgradeInvite(peerId: String) {
        room?.sendUpgradeRequest(peerId, status = false)
        _pendingUpgradeRequests.value = _pendingUpgradeRequests.value.filterNot { it.peerId == peerId }
        appendLog("⇢ cancelled upgrade invite to $peerId")
    }

    // =========================================================================
    // Waiting room
    // =========================================================================
    // Only relevant for password-protected rooms: a peer who supplies the wrong (or no)
    // password lands in a lobby until a moderator admits or rejects them.

    /** Moderator admits a peer waiting in the lobby. */
    fun admitPeer(peerId: String) {
        room?.allowRoomJoin(peerId)
        // Optimistic removal — RoomEvent.PeersWaiting will re-sync us with the server's
        // authoritative list on its next update regardless.
        _waitingPeers.value = _waitingPeers.value.filterNot { it.peerId == peerId }
        appendLog("⇢ admitted $peerId")
    }

    /** Moderator rejects a peer waiting in the lobby. */
    fun denyPeer(peerId: String) {
        room?.denyRoomJoin(peerId)
        _waitingPeers.value = _waitingPeers.value.filterNot { it.peerId == peerId }
        appendLog("⇢ denied $peerId")
    }

    // =========================================================================
    // Room settings (moderator only)
    // =========================================================================
    // Three independent forms, one per SDK call. None of these have a confirmation
    // event on Android (RN's version listens for a "roomSetting:*" custom-message echo
    // to sync UI across multiple moderators, but that echo path isn't wired on Android),
    // so this is fire-and-forget like the rest of the moderator action buttons.

    fun applyGeneralSettings(allowScreenShare: Boolean, noOfScreenShare: Int, noOfUpgradeRequests: Int) {
        room?.handleRoomSettingsGeneral(
            allowScreenShare    = allowScreenShare,
            noOfScreenShare     = noOfScreenShare,
            noOfUpgradeRequests = noOfUpgradeRequests,
        )
        appendLog("⇢ applied general room settings")
    }

    fun applyPresenterSettings(allowRaiseHand: Boolean, allowPublicChat: Boolean, allowPrivateChat: Boolean) {
        room?.handlePresenterSettings(
            allowPresenterRaiseHand   = allowRaiseHand,
            allowPresenterPublicChat  = allowPublicChat,
            allowPresenterPrivateChat = allowPrivateChat,
        )
        appendLog("⇢ applied presenter permissions")
    }

    fun applyParticipantSettings(allowRaiseHand: Boolean, allowPublicChat: Boolean, allowPrivateChat: Boolean) {
        room?.handleParticipantSettings(
            allowParticipantRaiseHand   = allowRaiseHand,
            allowParticipantPublicChat  = allowPublicChat,
            allowParticipantPrivateChat = allowPrivateChat,
        )
        appendLog("⇢ applied participant permissions")
    }

    // =========================================================================
    // Stage routing
    // =========================================================================
    // Android's stage API is peer-additive/removal (stagePeers/unstagePeers), unlike RN's
    // handleRoomSettingsStage() which resubmits the entire peer list every time. Neither
    // call has a confirmation event (StagePeersResponse only carries a raw JSONObject with
    // no documented shape), so "who's on stage" is tracked optimistically client-side —
    // same pattern as isMicMuted etc. elsewhere in this ViewModel.

    private val _isOnStage = MutableStateFlow(false)
    val isOnStage: StateFlow<Boolean> = _isOnStage.asStateFlow()

    /** Moderator assigns specific peers to the stage. */
    fun setStagePeers(peerIds: List<String>) {
        room?.stagePeers(peerIds)
        appendLog("⇢ staged: $peerIds")
    }

    /** Moderator removes specific peers from the stage. */
    fun removeStagePeers(peerIds: List<String>) {
        room?.unstagePeers(peerIds)
        appendLog("⇢ unstaged: $peerIds")
    }

    /** Moderator configures stage capacity/mode (not membership — see stagePeers above). */
    fun updateStageSettings(maxOnStage: Int?, stageMode: String?) {
        room?.handleRoomSettingsStage(maxOnStage, stageMode)
        appendLog("⇢ updated stage settings")
    }

    /** Self-service: move myself onto the stage. */
    fun goOnStage() {
        val myId = room?.peerId ?: return
        room?.stagePeers(listOf(myId))
        _isOnStage.value = true
        appendLog("⇢ went on stage")
    }

    /** Self-service: move myself back off the stage. */
    fun backStage() {
        val myId = room?.peerId ?: return
        room?.unstagePeers(listOf(myId))
        _isOnStage.value = false
        appendLog("⇢ moved back stage")
    }

    // =========================================================================
    // AI Agent
    // =========================================================================

    /** Moderator only: ask the server which streams can currently be added to the AI selection. */
    fun loadAiSelectableStreams() {
        room?.requestAiSelectableStreams()
        appendLog("⇢ requestAiSelectableStreams")
    }

    /** Moderator only: tick/untick one peer's streams in the picker. */
    fun toggleAiPeerTick(peerId: String) {
        _aiTickedPeers.value = if (peerId in _aiTickedPeers.value) {
            _aiTickedPeers.value - peerId
        } else {
            _aiTickedPeers.value + peerId
        }
    }

    /**
     * Sends the picker's CURRENT full tick-state. startAiAgent() is a complete
     * re-declaration of who the agent watches, not an incremental add — so this must
     * always be called with everything that should end up selected, not just what
     * changed. An empty selection is sent as stopAiAgent(), not startAiAgent(emptyList())
     * — the server explicitly refuses an empty targets list (a billing guard against
     * accidentally widening the analysis to the whole room).
     */
    fun applyAiSelection() {
        if (_aiTickedPeers.value.isEmpty()) {
            room?.stopAiAgent()
            appendLog("⇢ stopAiAgent")
        } else {
            room?.startAiAgent(targets = _aiTickedPeers.value.map { VideoRoom.AiTargetSpec(it) })
            appendLog("⇢ startAiAgent targets=${_aiTickedPeers.value}")
        }
    }

    /**
     * Answers the one pending consent request for this device. respondToAiConsent() is
     * all-or-nothing across every stream in that request (the SDK sends the same
     * [granted] value for each) — partial per-stream answers aren't part of this call;
     * use grantAiConsent()/revokeAiConsent() below to change a single stream later,
     * independent of this initial answer.
     */
    fun respondAiConsent(granted: Boolean) {
        room?.respondToAiConsent(granted)
        // Mirror the SDK's own bookkeeping — respondToAiConsent() clears its internal
        // pending request only after the frame is actually sent, so read it back rather
        // than assuming this call succeeded.
        _pendingAiConsent.value = room?.getPendingAiConsent()
        appendLog("⇢ respondToAiConsent granted=$granted")
    }

    /**
     * Per-stream answer — only [grantedMediaTags] are granted, every other
     * requested stream is denied. Matches Flutter's consent dialog, which
     * offers a checkbox per stream rather than one Allow/Decline for
     * everything; uses the `Set<String>` overload added to the SDK in the
     * same pass that found this app's dialog was still all-or-nothing.
     */
    fun respondAiConsent(grantedMediaTags: Set<String>) {
        room?.respondToAiConsent(grantedMediaTags)
        _pendingAiConsent.value = room?.getPendingAiConsent()
        appendLog("⇢ respondToAiConsent granted=$grantedMediaTags")
    }

    /** Self-service: grant consent for one of your own streams, independent of any prompt. */
    fun grantAiConsent(mediaTag: String) {
        room?.grantAiConsent(listOf(mediaTag))
        appendLog("⇢ grantAiConsent $mediaTag")
    }

    /** Self-service: revoke a consent you previously gave, mid-call. */
    fun revokeAiConsent(mediaTag: String) {
        room?.revokeAiConsent(listOf(mediaTag))
        appendLog("⇢ revokeAiConsent $mediaTag")
    }

    // =========================================================================
    // Permissions denied
    // =========================================================================

    fun onPermissionsDenied() {
        _ui.value = RoomUiState.Error("Camera and microphone permissions are required.")
    }

    fun dismissNotification() {
        _notification.value = null
    }

    // =========================================================================
    // Observe room flows
    // =========================================================================

    private fun observeRoom(r: VideoRoom) {

        // 1. WebSocket connection state → drive Connecting/Ready transitions
        viewModelScope.launch {
            r.connectionState.collect { state ->
                appendLog("• socket: ${state::class.simpleName}")
                when (state) {
                    is ConnectionState.Connected   ->
                        if (_ui.value is RoomUiState.Connecting) _ui.value = RoomUiState.Ready
                    is ConnectionState.Reconnected -> {
                        _isReconnecting.value = false
                        appendLog("• socket reconnected — ICE restart in progress")
                    }
                    is ConnectionState.Failed      -> {
                        if (_ui.value is RoomUiState.InCall) {
                            // Transient — SignallingClient is already retrying with its own
                            // exponential backoff (see socket.onReconnectExhausted in VideoRoom
                            // for the give-up path, surfaced below as RoomEvent.Error with code
                            // "SOCKET_RECONNECT_FAILED"). Don't kick the user out of a live call
                            // over what's usually a few seconds of dead air.
                            _isReconnecting.value = true
                        } else {
                            _ui.value = RoomUiState.Error(state.reason)
                        }
                    }
                    else -> {}
                }
            }
        }

        // 2. Room membership (NotJoined → Joining → Joined) → sync participants list
        viewModelScope.launch {
            r.roomState.collect { joinState ->
                when (joinState) {
                    is JoinState.Joined -> {
                        _participants.value = joinState.participants
                        appendLog("✓ room state: Joined (${joinState.participants.size} peers)")
                    }
                    else -> {}
                }
            }
        }

        // 3. Debug log: every raw server message (catches "invalidToken" etc.) — except
        // "currentlyActiveSpeaker", which the server sends on nearly every audio frame and
        // would otherwise fill the entire 60-line appendLog buffer by itself, burying every
        // other diagnostic line (join/leave/errors) that this log actually exists to surface.
        viewModelScope.launch {
            r.serverMessages.collect { msg ->
                if (msg.id != "currentlyActiveSpeaker") {
                    appendLog("⇠ ${msg.id.ifEmpty { "(no id)" }}")
                }
                if (msg.id == "invalidToken") {
                    _ui.value = RoomUiState.Error("Session token is invalid or expired")
                }
            }
        }

        // 4. All SDK events
        viewModelScope.launch {
            r.events.collect { event ->
                // LIVE-TEST logging (debug builds only): every SDK event, in arrival order, so a
                // device run can be checked from `adb logcat -s SamvyoEvent`. Event payloads carry
                // no credentials (tokens / TURN secrets never appear in RoomEvent).
                if (BuildConfig.DEBUG) android.util.Log.d("SamvyoEvent", event.toString())
                handleEvent(event, r)
            }
        }
        // LIVE-TEST: inbound server message IDS only (never the body — bodies carry
        // iceServers TURN credentials, N216). Debug builds only.
        if (BuildConfig.DEBUG) viewModelScope.launch {
            r.serverMessages.collect { android.util.Log.d("SamvyoWire", "<- ${it.id}") }
        }
    }

    private fun handleEvent(event: RoomEvent, r: VideoRoom) {
        when (event) {

            // Socket open, SDK ready — show Join button
            is RoomEvent.InitSuccess ->
                appendLog("✓ InitSuccess — socket open")

            // Server confirmed we are in the room → go to call screen
            is RoomEvent.JoinSuccess -> {
                _ui.value = RoomUiState.InCall
                appendLog("✓ JoinSuccess — MediaEngine starting…")
                // Seed AI state from the SDK's own synchronous getters — covers a
                // reconnect mid-call re-firing JoinSuccess after some of this was
                // already set server-side, so a freshly (re)mounted panel isn't blank.
                _pendingAiConsent.value = r.getPendingAiConsent()
                _myAiStreams.value = r.getAiMyConsent()?.streams ?: emptyList()
                _aiObservations.value = r.getAiObservations()
                if (_localPeerType.value == RoomConstants.PEER_MODERATOR) {
                    r.requestAiSelectableStreams()
                }
            }

            // Server couldn't admit us immediately (capacity/org isolation). Always followed
            // by either JoinRetryScheduled (which owns the user-facing "retrying…" banner
            // with an actual attempt count) or a terminal JoinFailed — log only here so we
            // don't show a notification that's immediately superseded by one of those two.
            is RoomEvent.JoinRejected ->
                appendLog("⇠ joinRejected: ${event.reason} — retryAfter=${event.retryAfterMs}ms")

            // SDK is about to retry a rejected join — gives an exact attempt count/delay,
            // unlike the raw JoinRejected above. We stay on RoomUiState.Joining; the retry
            // itself is entirely handled inside VideoRoom.
            is RoomEvent.JoinRetryScheduled -> {
                _notification.value =
                    "Room busy (${event.reason}) — retrying ${event.attempt}/${event.maxRetries} " +
                        "in ${event.delayMs / 1000}s…"
                appendLog("⇠ joinRetryScheduled: attempt ${event.attempt}/${event.maxRetries}, delay=${event.delayMs}ms")
            }

            // SDK exhausted all automatic join retries (or the rejection was non-retriable,
            // e.g. org capacity exhausted) — this is terminal. Without this branch the UI
            // would sit on "Joining…" forever, since nothing else transitions
            // RoomUiState.Joining away on this path.
            is RoomEvent.JoinFailed -> {
                val suffix = if (!event.retriable) " — contact support, retrying will not help." else ""
                _ui.value = RoomUiState.Error((event.message ?: "Join failed: ${event.reason}") + suffix)
                appendLog("✗ joinFailed: ${event.reason} (retriable=${event.retriable})")
            }

            // Local camera track arrived
            is RoomEvent.VideoStart -> {
                if (event.type == "local") {
                    _localVideoTrack.value = event.track
                    // enableCam() succeeded — a fresh producer exists again.
                    _isCameraOff.value = false
                    // Earliest point the camera capturer is guaranteed to exist —
                    // apply a join-screen camera choice now, exactly once.
                    preferredCamera?.let { selectVideoDevice(it) }
                    preferredCamera = null
                } else {
                    _remoteVideoTracks.value = _remoteVideoTracks.value + (event.peerId to event.track)
                }
                appendLog("• ${event.type} video started: ${event.peerId.take(8)}")
            }

            // A camera video track ended
            is RoomEvent.VideoEnd -> {
                if (event.type == "local") {
                    _localVideoTrack.value = null
                    // disableCam() closed our producer — reflect that in the UI.
                    _isCameraOff.value = true
                } else {
                    _remoteVideoTracks.value = _remoteVideoTracks.value - event.peerId
                }
                appendLog("• ${event.type} video ended: ${event.peerId.take(8)}")
            }

            // Remote peer's screen share video arrived
            is RoomEvent.SsVideoStart -> {
                if (event.type == "remote") {
                    _remoteScreenTracks.value = _remoteScreenTracks.value + (event.peerId to event.track)
                }
                appendLog("• screen share video from ${event.peerId.take(8)}")
            }

            // A screen share ended (remote or our own via ScreenShareStopped)
            is RoomEvent.SsVideoStop -> {
                _remoteScreenTracks.value = _remoteScreenTracks.value - event.peerId
                appendLog("• screen share stopped: ${event.peerId.take(8)}")
            }

            // Our own screen share confirmed started
            is RoomEvent.ScreenShareStarted -> {
                _isScreenSharing.value = true
                appendLog("✓ our screen share active")
            }

            // Our own screen share stopped
            is RoomEvent.ScreenShareStopped -> {
                _isScreenSharing.value = false
                stopScreenShareService()
                appendLog("• our screen share ended")
            }

            is RoomEvent.MicStart ->
                appendLog("• ${event.type} mic started: ${event.peerId.take(8)}")

            is RoomEvent.MicEnd ->
                appendLog("• ${event.type} mic ended: ${event.peerId.take(8)}")

            // Another peer toggled their mic or camera
            is RoomEvent.PeerMuted ->
                updatePeerMuteState(event.peerId, event.type, muted = true)

            is RoomEvent.PeerUnMuted ->
                updatePeerMuteState(event.peerId, event.type, muted = false)

            // Moderator force-muted us
            is RoomEvent.MicForcedOff -> {
                _isMicMuted.value = true
                _notification.value = event.message
                appendLog("• ${event.message}")
            }

            // Active speaker changed
            is RoomEvent.ActiveSpeaker -> {
                _activeSpeakerId.value = event.peerId
            }

            // New peer joined
            is RoomEvent.NewPeer ->
                appendLog("• new peer: ${event.peerName ?: event.peerId.take(8)} (${event.peerType})")

            // A peer left — clean up their tracks and mute state
            is RoomEvent.PeerLeft -> {
                _remoteVideoTracks.value  = _remoteVideoTracks.value  - event.peerId
                _remoteScreenTracks.value = _remoteScreenTracks.value - event.peerId
                _peerMuteStates.value     = _peerMuteStates.value     - event.peerId
                _handRaiseList.value      = _handRaiseList.value.filterNot { it.peerId == event.peerId }
                _pendingUpgradeRequests.value = _pendingUpgradeRequests.value.filterNot { it.peerId == event.peerId }
                _peerConnectionFailed.value = _peerConnectionFailed.value - event.peerId
                if (_activeSpeakerId.value == event.peerId) _activeSpeakerId.value = null
                appendLog("• peer left: ${event.peerId.take(8)}")
            }

            // P2P: ICE reached "connected" for a direct peer link — clear any prior
            // failed-badge for this peer since they've since recovered.
            is RoomEvent.PeerConnected -> {
                _peerConnectionFailed.value = _peerConnectionFailed.value - event.peerId
                appendLog("• P2P peer connected: ${event.peerId.take(8)}")
            }

            // P2P: ICE dropped for a direct peer link — often self-heals (mirrors the
            // transport-level "disconnected" handling used elsewhere in this app), so
            // this is log-only; PeerConnectionFailed below is the one that badges the tile.
            is RoomEvent.PeerDisconnected ->
                appendLog("• P2P peer disconnected: ${event.peerId.take(8)}")

            // P2P: ICE hard-failed for a direct peer link — will not self-heal on its own.
            // Badge the tile so this reads as a connection problem, not the peer simply
            // turning off their camera.
            is RoomEvent.PeerConnectionFailed -> {
                _peerConnectionFailed.value = _peerConnectionFailed.value + event.peerId
                appendLog("✗ P2P peer connection failed: ${event.peerId.take(8)}")
            }

            is RoomEvent.CallStartTime ->
                appendLog("• call start time: ${event.callStartTime}")

            // Recording started / stopped
            is RoomEvent.RecordingStarted -> {
                _isRecording.value = true
                appendLog("• recording started")
            }
            is RoomEvent.RecordingEnded -> {
                _isRecording.value = false
                appendLog("• recording ended")
            }

            // Recording is queued behind a P2P→SFU migration — RecordingStarted follows
            // once that completes. isRecordingActive deliberately stays false in between:
            // the "Stop REC" button would otherwise appear before anything is recording.
            is RoomEvent.RecordingMigrating -> {
                _notification.value = "Recording will start shortly…"
                appendLog("• recording migrating (P2P→SFU)")
            }

            // Live streaming started / stopped — same server-driven shape as recording above.
            is RoomEvent.LiveStreamingStarted -> {
                _isLiveStreaming.value = true
                _notification.value = "Live stream started"
                appendLog("• live streaming started")
            }
            is RoomEvent.LiveStreamingEnded -> {
                _isLiveStreaming.value = false
                _notification.value = "Live stream ended"
                appendLog("• live streaming ended")
            }

            // Transcription lifecycle — local flag flips, see the Transcription section above.
            is RoomEvent.TranscriptionStarted -> {
                _transcriptionActive.value = true
                appendLog("• transcription started")
            }
            is RoomEvent.TranscriptionStopped -> {
                _transcriptionActive.value = false
                appendLog("• transcription stopped")
            }

            // One caption line from the server's speech-to-text service. Keep last 50,
            // matching the RN app's cap for the same list.
            is RoomEvent.Transcription -> {
                val text = event.transcript ?: return
                _transcriptionEntries.value = (_transcriptionEntries.value + TranscriptionEntry(
                    speaker    = event.speaker,
                    transcript = text,
                    isFinal    = event.isFinal,
                )).takeLast(50)
            }

            is RoomEvent.TranscriptionError -> {
                _notification.value = "Transcription error: ${event.error}"
                appendLog("✗ transcription error: ${event.error}")
            }

            // Our hand raise confirmed by server
            is RoomEvent.HandRaised ->
                _handRaised.value = true

            // A hand was dropped (ours or another peer's)
            is RoomEvent.HandDropped -> {
                if (event.peerId == r.peerId) _handRaised.value = false
                _handRaiseList.value = _handRaiseList.value.filterNot { it.peerId == event.peerId }
                appendLog("• hand dropped: ${event.peerId.take(8)}")
            }

            // A peer raised (or lowered) their hand — keep the moderator's list in sync
            is RoomEvent.HandRaise -> {
                _handRaiseList.value = if (event.handRaised) {
                    _handRaiseList.value.filterNot { it.peerId == event.peerId } + HandRaiseEntry(
                        peerId         = event.peerId,
                        peerName       = event.peerName,
                        upgradeRequest = event.upgradeRequest,
                    )
                } else {
                    _handRaiseList.value.filterNot { it.peerId == event.peerId }
                }
                appendLog("• hand raise: ${event.peerName ?: event.peerId.take(8)} (upgrade=${event.upgradeRequest})")
            }

            // We were upgraded from viewer to presenter
            is RoomEvent.Upgraded ->
                appendLog("✓ we were upgraded to presenter")

            // We were downgraded back to viewer
            is RoomEvent.Downgraded ->
                appendLog("• we were downgraded to viewer")

            // A peer (not necessarily us) was upgraded to presenter — reflect the new role
            // in the participants list so moderator badges/action gating stay in sync.
            is RoomEvent.ParticipantUpgraded -> {
                updateParticipantType(event.peerId, RoomConstants.PEER_PARTICIPANT)
                // If we had an outstanding invite to this peer, it's now resolved (accepted).
                _pendingUpgradeRequests.value = _pendingUpgradeRequests.value.filterNot { it.peerId == event.peerId }
                appendLog("• participant upgraded: ${event.peerId.take(8)}")
            }

            // A peer was downgraded back to viewer
            is RoomEvent.ParticipantDowngraded -> {
                updateParticipantType(event.peerId, RoomConstants.PEER_VIEWER)
                appendLog("• participant downgraded: ${event.peerId.take(8)}")
            }

            // Our own sendUpgradeInvite() was sent — track it as awaiting response, or
            // (status=false, from cancelUpgradeInvite()) drop it — see VideoRoom.sendUpgradeRequest().
            is RoomEvent.UpgradeRequestSent -> {
                val targetId = event.peerId ?: return
                if (event.status) {
                    val name = _participants.value.firstOrNull { it.peerId == targetId }?.name
                    _pendingUpgradeRequests.value = _pendingUpgradeRequests.value
                        .filterNot { it.peerId == targetId } + UpgradeRequest(peerId = targetId, peerName = name)
                } else {
                    _pendingUpgradeRequests.value = _pendingUpgradeRequests.value.filterNot { it.peerId == targetId }
                }
                appendLog("⇠ upgrade invite sent to ${targetId.take(8)} (status=${event.status})")
            }

            // A moderator invited us to become a presenter — show as an Accept/Decline banner.
            is RoomEvent.UpgradeRequestReceived -> {
                _incomingUpgradeInvite.value = UpgradeRequest(
                    peerId          = event.peerId,
                    peerName        = null,
                    moderatorPeerId = event.moderator,
                )
                appendLog("⇠ upgrade invite received from ${event.moderator?.take(8)}")
            }

            // A peer we invited declined — drop them from our pending list.
            is RoomEvent.UpgradeRequestRejected -> {
                val rejectedPeerId = event.peerId
                if (rejectedPeerId != null) {
                    _pendingUpgradeRequests.value = _pendingUpgradeRequests.value.filterNot { it.peerId == rejectedPeerId }
                }
                _notification.value = "Upgrade invite declined"
                appendLog("⇠ upgrade invite rejected: ${rejectedPeerId?.take(8)}")
            }

            // The moderator withdrew the invite that was pending for us.
            is RoomEvent.UpgradeRequestCancelled -> {
                _incomingUpgradeInvite.value = null
                appendLog("⇠ upgrade invite cancelled")
            }

            // Moderator locked or unlocked the room
            is RoomEvent.RoomLockStatusChanged -> {
                _isRoomLocked.value = event.locked
                appendLog(if (event.locked) "• room locked" else "• room unlocked")
            }

            // Full lobby snapshot (not a delta) — replace, don't merge. See WaitingPeer's doc comment.
            is RoomEvent.PeersWaiting -> {
                _waitingPeers.value = event.peers.map { p ->
                    WaitingPeer(
                        peerId   = p.optString("peerId"),
                        peerName = p.optString("peerName").ifEmpty { null },
                    )
                }
                appendLog("• ${event.count} peer(s) waiting to join")
            }

            // Max simultaneous screen shares reached — our attempt was denied
            is RoomEvent.ScreenShareLimitReached -> {
                _notification.value = event.message
                appendLog("• screen share limit reached: ${event.message}")
            }

            // Max active presenters reached — the upgrade attempt was denied
            is RoomEvent.UpgradeLimitReached -> {
                _notification.value = event.message
                appendLog("• upgrade limit reached: ${event.message}")
            }

            // Incoming chat message
            is RoomEvent.CustomMessage -> {
                // The server echoes our own sendCustomMessage() back to us too. Both
                // sendChat() and sendReaction() already add an optimistic local entry
                // when we act, so without this guard our own message/reaction would be
                // added a second time once its echo arrives here. This guard was a
                // silent no-op until the matching SDK fix (VideoRoom.onCustomMessage()
                // now reads the sender identity from the wire's actual "from" key
                // instead of the never-sent "peerId") — before that, event.senderPeerId
                // was always null for every incoming custom message, self or otherwise,
                // so this app never double-counted but also never showed anyone else's
                // chat or reactions at all. See SDK_UPDATE_FIXES.md's 2026-08-24 entry.
                if (event.senderPeerId != null && event.senderPeerId == r.peerId) return

                if (event.type == "chat") {
                    val text = event.data?.toString() ?: return
                    val senderPeerId = event.senderPeerId ?: return
                    val senderName = _participants.value
                        .firstOrNull { it.peerId == senderPeerId }?.name
                        ?: senderPeerId.take(8)
                    _chatMessages.value = _chatMessages.value + ChatMessage(
                        senderPeerId = senderPeerId,
                        senderName   = senderName,
                        text         = text,
                        isLocal      = false,
                        // event.messageType carries back the same MSG_TYPE_PUBLIC/PRIVATE
                        // wire value we sent — used only to render the "(Private)" bubble
                        // label. See sendChat()'s doc comment: `type` identifies the
                        // message kind ("chat"), `messageType` carries the scope — this
                        // was inverted here until the 2026-08-24 fix.
                        scope        = event.messageType ?: RoomConstants.MSG_TYPE_PUBLIC,
                    )
                    appendLog("⇠ chat from $senderName: $text")
                } else {
                    // Not identified by messageType at all — see sendReaction()'s doc
                    // comment for why. event.data arrives as whatever the SDK's JSON
                    // parser produced: a String (RN's convention — JSON-stringified
                    // before sending) or a JSONObject (Flutter's convention — sent
                    // unstringified). Handle both so we can receive from either.
                    val obj = when (val data = event.data) {
                        is JSONObject -> data
                        is String -> runCatching { JSONObject(data) }.getOrNull()
                        else -> null
                    }
                    val emoji = obj?.takeIf { it.optString("type") == "emoji-reaction" }
                        ?.optString("emoji")?.takeIf { it.isNotEmpty() }
                    if (emoji != null) {
                        val senderPeerId = event.senderPeerId ?: return
                        val senderName = _participants.value
                            .firstOrNull { it.peerId == senderPeerId }?.name
                            ?: senderPeerId.take(8)
                        _recentReactions.value = (_recentReactions.value + EmojiReaction(
                            emoji    = emoji,
                            fromName = senderName,
                        )).takeLast(6)
                        appendLog("⇠ reaction from $senderName: $emoji")
                    }
                }
            }

            // Room ended
            is RoomEvent.RoomClosed -> {
                _notification.value = when (event.reason) {
                    "moderator_ended"      -> "The meeting was ended by the host."
                    "removed_by_moderator" -> "You were removed from the meeting."
                    // reason is null when the server gave none (JS/RN payload); show the
                    // stable category instead ("moderator_closed" / "duration_limit").
                    else                   -> "Room closed: ${event.reason ?: event.category}"
                }
                stopScreenShareService()
                resetCallState()
                appendLog("• room closed: ${event.reason}")
            }

            // Confirms our own leaveRoom()/closeRoom() went through. Log-only: by the time
            // this async event reaches us, leaveRoom()/closeRoom() below has already run
            // resetCallState() synchronously — this isn't the trigger for that transition.
            is RoomEvent.RoomLeft ->
                appendLog("• room left: ${event.roomId}")

            // Camera successfully switched
            is RoomEvent.VideoInputChanged ->
                appendLog("• camera → ${event.deviceLabel ?: event.deviceId}")

            // Reports which codec actually got negotiated for our video producer vs what was
            // requested. Log-only: this app never sets preferredVideoCodec (leaves it at the
            // "auto" default), so fellBack is structurally always false for us today — but the
            // SDK still emits this for every produce(), so it needs a case rather than falling
            // through to else.
            is RoomEvent.CodecProduced ->
                appendLog("• codec produced [${event.mediaTag}]: ${event.actual}" +
                    (if (event.fellBack) " (fell back from ${event.preferred})" else ""))

            // Server closed the socket with a per-client terminal code (rate limit, message
            // too large, send-queue overflow) — SignallingClient has already permanently
            // disabled its own reconnect loop for this. Reconnecting would just re-trip the
            // same server-side guard, so — unlike the transient ConnectionState.Failed handled
            // in the connectionState.collect block below — this must kick the user out even
            // mid-call; there is no self-healing path to wait for.
            is RoomEvent.TerminalClose -> {
                _isReconnecting.value = false
                // The SDK released the call (RN/JS close() parity): drop every tile so no
                // renderer stays bound to an ended track, exactly like RoomClosed below.
                // resetCallState() sets Idle, so the Error is set AFTER it.
                stopScreenShareService()
                resetCallState()
                _ui.value = RoomUiState.Error(
                    event.reason ?: "Connection closed (${event.category}, code ${event.code}). Please rejoin."
                )
                appendLog("✗ terminal close: ${event.category} (code=${event.code})")
            }

            // Autoscaler had no record of our peerId on reconnect (grace period elapsed, or
            // an autoscaler restart lost it) — producer/consumer state is gone server-side.
            // Per the SDK's own contract the app must leave and rejoin fresh; we can't just
            // wait this one out like a transient ConnectionState.Failed.
            is RoomEvent.SessionExpired -> {
                _isReconnecting.value = false
                // The SDK released the call (RN/JS close() parity): drop every tile so no
                // renderer stays bound to an ended track, exactly like RoomClosed below.
                // resetCallState() sets Idle, so the Error is set AFTER it.
                stopScreenShareService()
                resetCallState()
                _ui.value = RoomUiState.Error(
                    event.reason ?: "Your session expired (code ${event.code}). Please rejoin."
                )
                appendLog("✗ session expired: code=${event.code}")
            }

            // Connection state change — [reason] is set e.g. to "server_restart" when the
            // SFU autoscaler restarts and rebuildMedia silently re-registers our join.
            is RoomEvent.ConnectionStateChange -> {
                if (event.reason == "server_restart") {
                    _notification.value = "Server restarted — reconnecting…"
                }
                appendLog("• connection: ${event.status}" + (event.reason?.let { " ($it)" } ?: ""))
            }

            // EVENT-type rooms only — server pushes this ~1Hz as an audience-size badge.
            is RoomEvent.ViewerCount ->
                _viewerCount.value = event.count

            // P2P→SFU auto-migration starting — server-driven once the room crosses its
            // p2pThreshold peer count. Direct P2P links stay up while the SFU session spins
            // up underneath, so this is purely an informational banner, not a state change.
            is RoomEvent.RoomModeChanging -> {
                _isMigratingToSfu.value = true
                _notification.value = "Upgrading to group call…"
                appendLog("• room mode changing: ${event.from} → ${event.to}")
            }

            // Migration complete — SFU media is now active and all P2P links are torn down.
            is RoomEvent.RoomModeChanged -> {
                _isMigratingToSfu.value = false
                _notification.value = "Now in group call"
                appendLog("• room mode changed: ${event.from} → ${event.to}")
            }

            // Generic server info messages (notify / userError / alreadyActive /
            // passwordDisabled / cIdUpdate). Only userError is surfaced to the user —
            // the rest, cIdUpdate in particular, are internal bookkeeping, not something
            // worth interrupting the call for.
            is RoomEvent.Notification -> {
                appendLog("• notification [${event.eventType}]: ${event.eventText}")
                if (event.eventType == "userError" && event.eventText != null) {
                    _notification.value = event.eventText
                }
            }

            // SDK error
            is RoomEvent.Error -> {
                appendLog("✗ [${event.code}] ${event.text}")
                when {
                    // SignallingClient exhausted all reconnect attempts (see VideoRoom's
                    // socket.onReconnectExhausted). Unlike the transient ConnectionState.Failed
                    // banner in observeRoom() above, this is terminal — the socket will not
                    // retry again, so this is the one case that must still kick the user back
                    // to the join screen even mid-call.
                    event.code == "SOCKET_RECONNECT_FAILED" -> {
                        _isReconnecting.value = false
                        _ui.value = RoomUiState.Error(event.text)
                    }
                    // Non-fatal mid-call errors — surface as a dismissible banner, not a
                    // screen change. EID-prefixed codes are the SDK's own error-code family
                    // (covers ERR_MIC_ENABLE_FAILED etc.); the rest are non-EID codes from the
                    // reconnect/transport-robustness SDK work that would otherwise match
                    // neither branch here and go log-only. ICE_RESTART_FAILED is dead as of
                    // the transport-recreation change (superseded by TransportRestartFailed
                    // below) but harmless to keep matching in case older SDK builds still send it.
                    (event.code.startsWith("EID") ||
                        event.code == "ICE_RESTART_FAILED" ||
                        event.code == "TRANSPORT_CONNECT_FAILED" ||
                        event.code == "TRANSPORT_CREATE_FAILED" ||
                        event.code == "TRANSPORT_RECREATE_FAILED" ||
                        event.code == "SEND_TRACK_REJECTED") &&
                        _ui.value is RoomUiState.InCall -> {
                        _notification.value = "${event.code}: ${event.text}"
                    }
                    _ui.value !is RoomUiState.InCall ->
                        _ui.value = RoomUiState.Error("[${event.code}] ${event.text}")
                }
            }

            // Structured recording failure — replaces the generic RoomEvent.Error the SDK used
            // to send for this, so the app can tell a recording error apart from any other
            // error without string-matching. The SDK already reset its own recordingStartedByMe
            // flag before emitting this, so a retry via toggleRecording() will work immediately.
            is RoomEvent.RecordingError -> {
                _notification.value = event.title?.let { "$it: ${event.text}" } ?: event.text
                appendLog("✗ recording error [${event.type ?: "?"}/${event.reason ?: "?"}]: ${event.text}")
            }

            // ICE restart was rejected server-side (transport no longer exists) — the SDK has
            // already kicked off recreateSendTransport()/recreateRecvTransport() to recover, so
            // this is transient like ConnectionState.Failed above, not a dead end. Reuses the
            // same isReconnecting banner rather than a separate one for what's the same
            // "media is briefly interrupted while we recover" situation from the user's POV.
            is RoomEvent.TransportRestartFailed -> {
                _isReconnecting.value = true
                appendLog("• ${event.transportType} transport restart failed for " +
                    "${event.transportId.take(8)} — recreating…")
            }

            // Server closed our subscription to another peer's stream to free capacity for
            // a higher-priority stream — that peer did NOT turn off their camera/mic. Without
            // this, the tile just goes blank with no explanation (looks like a freeze/bug).
            is RoomEvent.ConsumerPreempted -> {
                val name = _participants.value.firstOrNull { it.peerId == event.senderPeerId }?.name
                    ?: event.senderPeerId?.take(8) ?: "a peer"
                _notification.value = "Stream from $name was paused to free capacity"
                appendLog("• consumer preempted: ${event.mediaTag ?: "?"} " +
                    "(peer=${event.senderPeerId?.take(8)}, by=${event.preemptedBy?.take(8)})")
            }

            // Server refused to let us consume a peer's track — log-only so a denial is
            // visible during testing instead of the tile just silently never appearing.
            is RoomEvent.ConsumerAdmissionDenied ->
                appendLog("⇠ consumer admission denied: ${event.data}")

            // Authoritative, server-pushed role change (fires on a 4410/4411 close code,
            // before the reconnect finishes) — earlier than this app's own local
            // _localPeerType bookkeeping. Log-only for now; not urgent to replace the
            // existing local tracking, same conclusion the SDK's own doc comment draws.
            is RoomEvent.ParticipantTypeChanged ->
                appendLog("⇠ role changed: ${event.participantType} (${event.reason})")

            // ── AI Agent ──────────────────────────────────────────────────────────
            // See SDK_UPDATE_FIXES.md's 2026-08-24 entry for the full design.

            is RoomEvent.AiAgentStarted -> {
                _isAiAgentActive.value = true
                appendLog("⇠ AI agent started: attached=${event.attached}/${event.requested} wholeRoom=${event.wholeRoom}")
            }

            is RoomEvent.AiAgentError -> {
                if (event.retracted) _isAiAgentActive.value = false
                _notification.value = event.text ?: "AI agent error"
                appendLog("✗ AI agent error: ${event.text} (retracted=${event.retracted})")
            }

            is RoomEvent.AiEnded -> {
                _isAiAgentActive.value = false
                appendLog("⇠ AI agent ended: ${event.reason ?: "?"}")
            }

            // A consent window opened, narrowed, or closed for THIS device — always
            // re-read from the SDK's own copy rather than tracking it independently.
            // VideoRoom narrows/clears its pending request itself on aiConsentExpired,
            // so this never needs a client-side timer or a guess at when it closed.
            is RoomEvent.AiConsentRequest -> {
                _pendingAiConsent.value = event
                _aiConsentError.value = null
                appendLog("⇠ AI consent requested: ${event.streams.map { it.mediaTag }}")
            }
            is RoomEvent.AiConsentExpired -> {
                _pendingAiConsent.value = r.getPendingAiConsent()
                appendLog("⇠ AI consent expired: ${event.streams.map { it.mediaTag }}")
            }
            is RoomEvent.AiConsentNotApplied -> {
                _aiConsentError.value = "That request has already closed, so your answer was not applied."
                appendLog("⇠ AI consent not applied: ${event.streams.map { it.mediaTag }}")
            }

            is RoomEvent.AiSelectableStreams -> {
                _aiSelectableStreams.value = event.streams
                appendLog("⇠ AI selectable streams: ${event.streams.size}")
            }

            is RoomEvent.AiMyConsent -> {
                _myAiStreams.value = event.streams
                appendLog("⇠ AI my-consent: ${event.streams.size} stream(s)")
            }

            // One AI analysis frame — keep the last 50, same cap the SDK's own ring buffer uses.
            is RoomEvent.AiAnalysis ->
                _aiObservations.value = (_aiObservations.value + event).takeLast(50)

            is RoomEvent.AiState ->
                appendLog("⇠ AI state: ${event.state}" + (event.utterance?.let { " — $it" } ?: ""))

            is RoomEvent.AiError ->
                appendLog("✗ AI error: ${event.data}")

            else -> {}
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun updateParticipantType(peerId: String, newType: String) {
        _participants.value = _participants.value.map { peer ->
            if (peer.peerId == peerId) peer.copy(participantType = newType) else peer
        }
    }

    private fun updatePeerMuteState(peerId: String, type: String, muted: Boolean) {
        val current = _peerMuteStates.value.toMutableMap()
        val existing = current[peerId] ?: PeerMuteState()
        current[peerId] = when (type) {
            "audio" -> existing.copy(isAudioMuted = muted)
            "video" -> existing.copy(isVideoMuted = muted)
            else    -> existing
        }
        _peerMuteStates.value = current
    }

    private fun resetCallState() {
        room = null
        _localVideoTrack.value    = null
        _remoteVideoTracks.value  = emptyMap()
        _remoteScreenTracks.value = emptyMap()
        _participants.value       = emptyList()
        _peerMuteStates.value     = emptyMap()
        _activeSpeakerId.value    = null
        _chatMessages.value       = emptyList()
        _handRaiseList.value      = emptyList()
        _recentReactions.value    = emptyList()
        _selectedChatReceiver.value = null
        _pendingUpgradeRequests.value = emptyList()
        _incomingUpgradeInvite.value  = null
        _waitingPeers.value           = emptyList()
        _isMicMuted.value         = false
        _isCameraOff.value        = false
        _isScreenSharing.value    = false
        _isRecording.value        = false
        _handRaised.value         = false
        _isLiveStreaming.value    = false
        _transcriptionActive.value = false
        _transcriptionEntries.value = emptyList()
        _isOnStage.value = false
        _eglContext.value         = null
        _localPeerType.value      = null
        _isRoomLocked.value       = false
        _isReconnecting.value     = false
        _viewerCount.value        = null
        _isMigratingToSfu.value   = false
        _peerConnectionFailed.value = emptySet()
        _aiSelectableStreams.value = emptyList()
        _aiTickedPeers.value      = emptySet()
        _isAiAgentActive.value    = false
        _pendingAiConsent.value   = null
        _aiConsentError.value     = null
        _myAiStreams.value        = emptyList()
        _aiObservations.value     = emptyList()
        isFrontCamera             = true
        _ui.value                 = RoomUiState.Idle
    }

    private fun appendLog(line: String) {
        _log.value = (_log.value + line).takeLast(60)
    }

    override fun onCleared() {
        super.onCleared()
        room?.leaveRoom()
        stopScreenShareService()
    }

    // =========================================================================
    // Session token — fetched from a customer-run server, not embedded keys
    // =========================================================================
    // Mirrors the RN reference app's fetchSessionToken(): POST {roomId} to
    // BuildConfig.TOKEN_SERVER_URL (react-native-demo-app-server pattern — that server
    // holds ACCESS_KEY/SECRET_ACCESS_KEY in its own env and calls Samvyo's real
    // /api/siteSetting/sessionToken itself), parse {success, sessionToken} from the response.

    private suspend fun fetchSessionTokenFromServer(roomId: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("roomId", roomId).toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${BuildConfig.TOKEN_SERVER_URL}/api/create-session-token")
                    .post(body)
                    .build()
                tokenHttpClient.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string().orEmpty())
                    if (!response.isSuccessful || !json.optBoolean("success")) {
                        // react-native-demo-app-server always sends its error text under
                        // "message", never "error" (confirmed by reading server.js directly)
                        // — reading "error" here silently dropped every real failure reason
                        // ("roomId is required", "Invalid or expired token", etc.) in favor
                        // of a generic fallback string.
                        error(json.optString("message").ifEmpty { "Token server returned ${response.code}" })
                    }
                    json.getString("sessionToken")
                }
            }
        }

    companion object {
        private val tokenHttpClient = OkHttpClient()
    }
}
