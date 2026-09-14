package io.samvyo.demo

import java.util.UUID

/**
 * A single chat message displayed in the chat panel.
 * The SDK delivers chat via RoomEvent.CustomMessage — we convert it to this
 * app-level model so the UI doesn't need to know about the SDK's CustomMessage format.
 *
 * [scope] mirrors the wire "type" field (RoomConstants.MSG_TYPE_PUBLIC/PRIVATE) — the
 * server itself decides who receives a private message, so this is only used client-side
 * to render the "(Private)" label, not for any filtering.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderPeerId: String,
    val senderName: String,
    val text: String,
    val isLocal: Boolean,
    val scope: String = "public",
)

/**
 * Live mute state for one remote peer.
 * Updated from RoomEvent.PeerMuted / PeerUnMuted events.
 * Used to show mic and camera icons on each participant's video tile.
 */
data class PeerMuteState(
    val isAudioMuted: Boolean = false,
    val isVideoMuted: Boolean = false,
)

/**
 * One entry in the "Hands Raised" list shown to the moderator.
 * [upgradeRequest] = true means the peer also wants to be upgraded to presenter,
 * not just recognized (RoomEvent.HandRaise carries this from the server).
 */
data class HandRaiseEntry(
    val peerId: String,
    val peerName: String?,
    val upgradeRequest: Boolean,
)

/**
 * One received (or sent) emoji reaction, shown briefly in the corner of the call screen.
 * Delivered via RoomEvent.CustomMessage with messageType == "emoji".
 */
data class EmojiReaction(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String,
    val fromName: String,
)

/**
 * One moderator→peer upgrade invitation, tracked on both ends of the same flow
 * (VideoRoom.kt's onModUpgradeReq(): the wire message is symmetric, only the
 * recipient's role decides which RoomEvent it becomes):
 *  - On the moderator's device: one entry per invite sent, awaiting the peer's
 *    response (UpgradeRequestSent added it; Rejected/ParticipantUpgraded remove it).
 *  - On the invited peer's device: [moderatorPeerId] is set, and it represents the
 *    single incoming invite shown as an Accept/Decline banner.
 */
data class UpgradeRequest(
    val peerId: String,
    val peerName: String?,
    val moderatorPeerId: String? = null,
)

/**
 * A peer waiting in a password-protected room's lobby, shown to the moderator with
 * Admit/Reject buttons. RoomEvent.PeersWaiting delivers the *entire* current list each
 * time (not a delta), so the ViewModel just replaces _waitingPeers wholesale on receipt.
 */
data class WaitingPeer(
    val peerId: String,
    val peerName: String?,
)

/**
 * One camera reported by Android's Camera2Enumerator. The SDK's changeVideoInput(name)
 * takes this same [id] string directly — there's no separate SDK-side device-listing API
 * (unlike audio, where changeAudioInput() is a parameterless stub, see the Group B SDK-gap
 * note from the RN-parity review — this is why only video gets a device picker, not audio).
 */
data class VideoDevice(
    val id: String,
    val label: String,
    val isFront: Boolean,
)

/**
 * One line of live captions. Entirely server-driven — RoomEvent.Transcription arrives
 * from the server's speech-to-text service (Deepgram); the phone's own audio never has
 * to be captured/uploaded manually, so unlike RN's demo there's no sendAudioForTranscription
 * wiring here (checked: RN's bundled SDK doesn't call it either, despite exposing the method).
 */
data class TranscriptionEntry(
    val id: String = UUID.randomUUID().toString(),
    val speaker: String?,
    val transcript: String,
    val isFinal: Boolean,
)
