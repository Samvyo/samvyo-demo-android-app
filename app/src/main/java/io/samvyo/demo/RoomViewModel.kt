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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
            val token = VideoSdk.fetchSessionToken(
                accessKey       = BuildConfig.ACCESS_KEY,
                secretAccessKey = BuildConfig.SECRET_ACCESS_KEY,
                roomId          = roomId,
            ).getOrElse {
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

    fun toggleCamera() {
        val newOff = !_isCameraOff.value
        _isCameraOff.value = newOff
        room?.setCameraEnabled(!newOff)
        appendLog(if (newOff) "• camera off" else "• camera on")
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
        room?.sendCustomMessage(
            data           = trimmed,
            // A receiver picked → private + routed to that one peer, matching how the
            // RN app's "Sending to: X" picker drives the exact same two fields.
            type           = if (target != null) RoomConstants.MSG_TYPE_PRIVATE else RoomConstants.MSG_TYPE_PUBLIC,
            receiverPeerId = target,
            messageType    = "chat",
        )
        _chatMessages.value = _chatMessages.value + ChatMessage(
            senderPeerId = room?.peerId ?: "",
            senderName   = room?.peerName ?: "Me",
            text         = trimmed,
            isLocal      = true,
            scope        = if (target != null) RoomConstants.MSG_TYPE_PRIVATE else RoomConstants.MSG_TYPE_PUBLIC,
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

    fun sendReaction(emoji: String) {
        room?.sendCustomMessage(
            data        = emoji,
            type        = RoomConstants.MSG_TYPE_PUBLIC,
            messageType = "emoji",
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

    fun applyPresenterSettings(allowMic: Boolean, allowCamera: Boolean, allowScreenShare: Boolean) {
        room?.handlePresenterSettings(
            allowMic         = allowMic,
            allowCamera      = allowCamera,
            allowScreenShare = allowScreenShare,
        )
        appendLog("⇢ applied presenter permissions")
    }

    fun applyParticipantSettings(allowChat: Boolean, allowHandRaise: Boolean) {
        room?.handleParticipantSettings(
            allowChat      = allowChat,
            allowHandRaise = allowHandRaise,
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
                    is ConnectionState.Reconnected ->
                        appendLog("• socket reconnected — ICE restart in progress")
                    is ConnectionState.Failed      ->
                        _ui.value = RoomUiState.Error(state.reason)
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
            r.events.collect { event -> handleEvent(event, r) }
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
            }

            // Local camera track arrived
            is RoomEvent.VideoStart -> {
                if (event.type == "local") {
                    _localVideoTrack.value = event.track
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
                if (_activeSpeakerId.value == event.peerId) _activeSpeakerId.value = null
                appendLog("• peer left: ${event.peerId.take(8)}")
            }

            // Recording started / stopped
            is RoomEvent.RecordingStarted -> {
                _isRecording.value = true
                appendLog("• recording started")
            }
            is RoomEvent.RecordingEnded -> {
                _isRecording.value = false
                appendLog("• recording ended")
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
                if (event.messageType == "chat") {
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
                        // event.type carries back the same MSG_TYPE_PUBLIC/PRIVATE wire
                        // value we sent — used only to render the "(Private)" bubble label.
                        scope        = event.type ?: RoomConstants.MSG_TYPE_PUBLIC,
                    )
                    appendLog("⇠ chat from $senderName: $text")
                } else if (event.messageType == "emoji") {
                    // Same envelope as chat, routed by messageType instead — see sendReaction().
                    val emoji = event.data?.toString() ?: return
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

            // Room ended
            is RoomEvent.RoomClosed -> {
                _notification.value = when (event.reason) {
                    "moderator_ended"      -> "The meeting was ended by the host."
                    "removed_by_moderator" -> "You were removed from the meeting."
                    else                   -> "Room closed: ${event.reason}"
                }
                stopScreenShareService()
                resetCallState()
                appendLog("• room closed: ${event.reason}")
            }

            // Camera successfully switched
            is RoomEvent.VideoInputChanged ->
                appendLog("• camera → ${event.deviceLabel ?: event.deviceId}")

            // Connection state change
            is RoomEvent.ConnectionStateChange ->
                appendLog("• connection: ${event.status}")

            // SDK error
            is RoomEvent.Error -> {
                appendLog("✗ [${event.code}] ${event.text}")
                // Only surface fatal errors as UI errors; non-fatal ones go to log only
                if (event.code.startsWith("EID") && _ui.value is RoomUiState.InCall) {
                    _notification.value = "${event.code}: ${event.text}"
                } else if (_ui.value !is RoomUiState.InCall) {
                    _ui.value = RoomUiState.Error("[${event.code}] ${event.text}")
                }
            }

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
}
