package io.samvyo.demo

import io.samvyo.sdk.Participant
import org.webrtc.VideoTrack

// Every possible state of the room screen.
// The UI composable switches on this to decide what to render.
sealed class RoomUiState {
    // Waiting for the user to type a room ID and tap "Init Room".
    object Idle : RoomUiState()

    // Token fetch + WebSocket handshake in progress.
    object Initializing : RoomUiState()

    // WebSocket is open. User can now tap "Join Room".
    object Ready : RoomUiState()

    // joinRoom message sent, waiting for server's existingParticipants reply.
    object Joining : RoomUiState()

    // Server confirmed we are in the room. Transports are being negotiated.
    // This state is visible briefly (a second or two) before the call goes live.
    data class Joined(
        val roomDisplayName: String?,
        val participants: List<Participant>,
        val hasRouterCaps: Boolean,
    ) : RoomUiState()

    // Live video call — both local camera and remote peer tracks are available.
    data class InCall(
        val localVideoTrack: VideoTrack?,
        val remoteVideoTracks: Map<String, VideoTrack>,
    ) : RoomUiState()

    // Something went wrong — message is shown to the user on screen.
    data class Error(val message: String) : RoomUiState()
}
