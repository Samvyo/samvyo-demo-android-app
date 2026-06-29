package io.samvyo.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.samvyo.sdk.JoinState
import io.samvyo.sdk.SamvyoRoom
import io.samvyo.sdk.SamvyoSdk
import io.samvyo.sdk.media.CallState
import io.samvyo.sdk.media.SamvyoMediaEngine
import io.samvyo.sdk.signalling.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase

// AndroidViewModel vs plain ViewModel:
//   SamvyoMediaEngine needs an Application Context to open the camera (Camera2Enumerator).
//   AndroidViewModel provides getApplication() — a context that is safe to hold long-term.
class RoomViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow<RoomUiState>(RoomUiState.Idle)
    val ui: StateFlow<RoomUiState> = _ui.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isCameraOff = MutableStateFlow(false)
    val isCameraOff: StateFlow<Boolean> = _isCameraOff.asStateFlow()

    // One shared GPU context for the whole call session.
    // Camera writes frames into this context; SurfaceViewRenderer reads from it.
    // Both must share the SAME EglBase or they cannot see each other's GPU textures.
    // Owned here (not in the engine) so the UI Composable can also access it,
    // and so it survives if the user stops/restarts the call without leaving the screen.
    val eglBase: EglBase = EglBase.create()

    private var room: SamvyoRoom? = null
    private var mediaEngine: SamvyoMediaEngine? = null

    // ─── Room lifecycle (token → socket → join) ───────────────────────────────

    fun initRoom(roomId: String, peerName: String) {
        if (roomId.isBlank()) {
            _ui.value = RoomUiState.Error("Room ID cannot be empty")
            return
        }
        _ui.value = RoomUiState.Initializing
        appendLog("Init: fetching session token…")

        viewModelScope.launch {
            // Step 1: exchange API keys for a JWT session token.
            val token = SamvyoSdk.fetchSessionToken(
                accessKey = BuildConfig.ACCESS_KEY,
                secretAccessKey = BuildConfig.SECRET_ACCESS_KEY,
                roomId = roomId,
            ).getOrElse {
                _ui.value = RoomUiState.Error(it.message ?: "Token fetch failed")
                appendLog("✗ ${it.message}")
                return@launch
            }
            appendLog("Token received. Opening WebSocket…")

            // Step 2: decode JWT, create SamvyoRoom, open WebSocket.
            val newRoom = try {
                SamvyoSdk.init(token, roomId, peerName)
            } catch (e: Exception) {
                _ui.value = RoomUiState.Error(e.message ?: "Init failed")
                return@launch
            }
            room = newRoom
            observeRoom(newRoom)
        }
    }

    fun joinRoom() {
        val room = room ?: return
        _ui.value = RoomUiState.Joining
        appendLog("⇢ joinRoom")
        room.joinRoom()
    }

    fun leaveRoom() {
        stopCall()
        room?.leaveRoom()
        room = null
        _ui.value = RoomUiState.Idle
        appendLog("⇢ leaveRoomNew")
    }

    fun toggleMic() {
        val newMuted = !_isMicMuted.value
        _isMicMuted.value = newMuted
        mediaEngine?.setMicEnabled(!newMuted)
        appendLog(if (newMuted) "• mic muted" else "• mic unmuted")
    }

    fun toggleCamera() {
        val newOff = !_isCameraOff.value
        _isCameraOff.value = newOff
        mediaEngine?.setCameraEnabled(!newOff)
        appendLog(if (newOff) "• camera off" else "• camera on")
    }

    fun onPermissionsDenied() {
        _ui.value = RoomUiState.Error("Camera and microphone permissions are required for calls.")
    }

    // ─── Call lifecycle (auto-started when existingParticipants arrives) ───────

    // Mirrors RN SDK: onExistingParticipants() auto-starts _createSendTransport().
    // No separate "Start Call" button — it starts automatically when joined.
    // Guarded: if engine already exists, returns early (safe to call multiple times).
    private fun startCall() {
        val currentRoom = room ?: return
        if (mediaEngine != null) return

        val engine = SamvyoMediaEngine(
            room = currentRoom,
            eglBase = eglBase,
            context = getApplication(),
        )
        mediaEngine = engine

        viewModelScope.launch {
            engine.callState.collect { callState ->
                when (callState) {
                    is CallState.Starting ->
                        appendLog("• media: negotiating transports…")

                    is CallState.InCall -> {
                        appendLog("• media: live (remote peers: ${callState.remoteVideoTracks.size})")
                        _ui.value = RoomUiState.InCall(
                            localVideoTrack = callState.localVideoTrack,
                            remoteVideoTracks = callState.remoteVideoTracks,
                        )
                    }

                    is CallState.Ended -> {
                        mediaEngine = null
                        val endError = callState.error
                        if (endError != null) {
                            appendLog("✗ media error: $endError")
                            _ui.value = RoomUiState.Error(endError)
                        } else {
                            appendLog("• media: call ended")
                            val rs = room?.roomState?.value
                            if (rs is JoinState.Joined) {
                                _ui.value = RoomUiState.Joined(
                                    roomDisplayName = rs.roomDisplayName,
                                    participants = rs.participants,
                                    hasRouterCaps = rs.hasRouterRtpCapabilities,
                                )
                            }
                        }
                    }

                    else -> {}
                }
            }
        }

        engine.start()
    }

    fun stopCall() {
        mediaEngine?.stop()
        mediaEngine = null
        _isMicMuted.value = false
        _isCameraOff.value = false
    }

    // ─── Observe socket and room state ────────────────────────────────────────

    private fun observeRoom(room: SamvyoRoom) {
        // Watcher 1: WebSocket connection state (Connecting → Connected → Closed/Failed)
        viewModelScope.launch {
            room.connectionState.collect { state ->
                appendLog("• socket: ${state::class.simpleName}")
                when (state) {
                    is ConnectionState.Connected ->
                        if (_ui.value is RoomUiState.Initializing) _ui.value = RoomUiState.Ready
                    is ConnectionState.Failed ->
                        _ui.value = RoomUiState.Error(state.reason)
                    else -> {}
                }
            }
        }

        // Watcher 2: Room membership state (NotJoined → Joining → Joined)
        viewModelScope.launch {
            room.roomState.collect { join ->
                when (join) {
                    is JoinState.Joined -> {
                        appendLog(
                            "✓ joined: room=${join.roomDisplayName} peers=${join.participants.size}",
                        )
                        // startCall() is guarded — safe to call on every participant change.
                        startCall()
                    }
                    else -> {}
                }
            }
        }

        // Watcher 3: Every raw server message (for the debug log on screen)
        viewModelScope.launch {
            room.serverMessages.collect { msg ->
                appendLog("⇠ ${msg.id.ifEmpty { "(no id)" }}")
                if (msg.id == "invalidToken") _ui.value = RoomUiState.Error("Session token invalid")
            }
        }
    }

    // ─── Cleanup — order matters ──────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        mediaEngine?.stop()   // 1. close camera, producers, transports, PeerConnectionFactory
        room?.leaveRoom()     // 2. send leaveRoomNew, close WebSocket
        eglBase.release()     // 3. release GPU context LAST — used by both above
    }

    private fun appendLog(line: String) {
        _log.value = (_log.value + line).takeLast(50)
    }
}
