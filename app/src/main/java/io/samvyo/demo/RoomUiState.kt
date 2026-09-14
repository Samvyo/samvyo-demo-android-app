package io.samvyo.demo

/**
 * The six phases the room screen can be in.
 *
 * All track/participant/chat data lives in separate StateFlows on RoomViewModel —
 * not inside these state objects. This keeps transitions simple: the UI just
 * switches on this value to decide which screen to show.
 *
 * Transition order:
 *   Idle → Connecting → Ready → Joining → InCall
 *                                           ↓
 *                                         Idle  (after leave/close/error)
 */
sealed class RoomUiState {
    /** Initial state. No room session exists. */
    object Idle : RoomUiState()

    /** fetchSessionToken + WebSocket handshake in progress. */
    object Connecting : RoomUiState()

    /** Socket open. User can now tap "Join Room". */
    object Ready : RoomUiState()

    /** joinRoom() sent to server, waiting for existingParticipants reply. */
    object Joining : RoomUiState()

    /** Server confirmed join (JoinSuccess). MediaEngine started. Full call UI shown. */
    object InCall : RoomUiState()

    /** Something went wrong. [message] is shown on screen. */
    data class Error(val message: String) : RoomUiState()
}
