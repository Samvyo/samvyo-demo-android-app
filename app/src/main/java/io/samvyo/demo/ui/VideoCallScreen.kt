package io.samvyo.demo.ui

import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.samvyo.demo.RoomViewModel
import io.samvyo.sdk.RoomConstants

// Same 9-emoji set as the RN reference app's engagement row.
private val ReactionEmojis = listOf("👍", "❤️", "🎉", "👏", "🔥", "😂", "😮", "🙌", "✨")

/**
 * Everything below the join fields, laid out as one continuous set of sections —
 * mirrors the RN reference app's structure exactly: App.js renders join controls and
 * call controls in a single, always-mounted ScrollView, not gated behind having joined.
 * MainActivity's RoomScreen embeds this unconditionally inside its own scrollable Column.
 *
 * [peerType] is the *selected* role from the join screen's "Join As" chips — not
 * necessarily a confirmed joined role. This matches RN, which has no separate
 * "confirmed" role concept: `resolvedPeerType`/`isModeratorRole` are derived straight
 * from the same pre-join selector state throughout the RN app's whole lifecycle, so
 * e.g. Presenter/Participant/General Permissions panels are already visible the moment
 * "Moderator" is selected, before Init Room is even tapped.
 *
 * [roomExists] mirrors RN's `sdkInstanceRef.current` truthiness — gates only the
 * media-control buttons (mute/camera/screen-share/record); every other section here
 * (Engagement, Chat, Stage Routing, permission panels, Transcription/Live Streaming)
 * is unconditional in RN and stays unconditional here too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallSections(vm: RoomViewModel, peerType: String, roomExists: Boolean) {

    val eglContext       by vm.eglContext.collectAsState()
    val localTrack       by vm.localVideoTrack.collectAsState()
    val remoteTracks     by vm.remoteVideoTracks.collectAsState()
    val screenTracks     by vm.remoteScreenTracks.collectAsState()
    val participants     by vm.participants.collectAsState()
    val peerMuteStates   by vm.peerMuteStates.collectAsState()
    val activeSpeakerId  by vm.activeSpeakerId.collectAsState()
    val isMicMuted       by vm.isMicMuted.collectAsState()
    val isCameraOff      by vm.isCameraOff.collectAsState()
    val isScreenSharing  by vm.isScreenSharing.collectAsState()
    val isRecording      by vm.isRecording.collectAsState()
    val isLiveStreaming  by vm.isLiveStreaming.collectAsState()
    val transcriptionActive  by vm.transcriptionActive.collectAsState()
    val transcriptionEntries by vm.transcriptionEntries.collectAsState()
    val handRaised       by vm.handRaised.collectAsState()
    val chatMessages     by vm.chatMessages.collectAsState()
    // Selected role (join screen), not the confirmed-joined role — see the doc comment above.
    val isModerator      = peerType == RoomConstants.PEER_MODERATOR
    val isRoomLocked      by vm.isRoomLocked.collectAsState()
    val isReconnecting    by vm.isReconnecting.collectAsState()
    val viewerCount       by vm.viewerCount.collectAsState()
    val isMigratingToSfu  by vm.isMigratingToSfu.collectAsState()
    val peerConnectionFailed by vm.peerConnectionFailed.collectAsState()
    val handRaiseList     by vm.handRaiseList.collectAsState()
    val recentReactions   by vm.recentReactions.collectAsState()
    val selectedChatReceiver by vm.selectedChatReceiver.collectAsState()
    val pendingUpgradeRequests by vm.pendingUpgradeRequests.collectAsState()
    val incomingUpgradeInvite  by vm.incomingUpgradeInvite.collectAsState()
    val waitingPeers            by vm.waitingPeers.collectAsState()
    val isOnStage               by vm.isOnStage.collectAsState()
    val aiSelectableStreams by vm.aiSelectableStreams.collectAsState()
    val aiTickedPeers        by vm.aiTickedPeers.collectAsState()
    val isAiAgentActive      by vm.isAiAgentActive.collectAsState()
    val pendingAiConsent     by vm.pendingAiConsent.collectAsState()
    val aiConsentError       by vm.aiConsentError.collectAsState()
    val myAiStreams          by vm.myAiStreams.collectAsState()
    val aiObservations       by vm.aiObservations.collectAsState()

    val videoDevices = remember { vm.listVideoDevices() }

    var showChatTargetPicker  by remember { mutableStateOf(false) }
    var showVideoDevicePicker by remember { mutableStateOf(false) }
    var chatInput by remember { mutableStateOf("") }
    var stagePeersInput     by remember { mutableStateOf("") }
    var backStagePeersInput by remember { mutableStateOf("") }
    var liveStreamUrl by remember { mutableStateOf("") }
    var liveStreamKey by remember { mutableStateOf("") }

    // Local-only permission form state — same pattern as the RN app: these are just the
    // values the moderator is about to Save, not a live sync of the room's actual settings
    // (Android has no confirmation event for these, see RoomViewModel's Room settings section).
    var allowScreenShare  by remember { mutableStateOf(true) }
    var noOfScreenShare   by remember { mutableStateOf("1") }
    var noOfUpgradeReq    by remember { mutableStateOf("5") }
    var allowPresenterRaiseHand by remember { mutableStateOf(true) }
    var allowPresenterPublicChat by remember { mutableStateOf(true) }
    var allowPresenterPrivateChat by remember { mutableStateOf(true) }
    var allowParticipantRaiseHand by remember { mutableStateOf(true) }
    var allowParticipantPublicChat by remember { mutableStateOf(true) }
    var allowParticipantPrivateChat by remember { mutableStateOf(true) }

    val context = LocalContext.current
    val screenShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Passed on also when cancelled: the SDK answers with a typed Error (EID013), shown in the log.
        vm.startScreenShare(result.resultCode, result.data)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HorizontalDivider()

        // ── Video ──────────────────────────────────────────────────────────────
        val egl = eglContext
        if (egl != null) {
            if (localTrack != null && !isCameraOff) {
                Text(
                    text  = if (isMicMuted) "You 🔇" else "You",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isMicMuted) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                )
                // Box lets us layer the mute badge over the video tile without
                // a separate Row/Column that would add unwanted spacing.
                Box {
                    VideoRenderer(
                        track      = localTrack!!,
                        eglContext = egl,
                        mirror     = true,
                        modifier   = Modifier.fillMaxWidth().height(200.dp),
                    )
                    if (isMicMuted) {
                        Text(
                            text     = "🔇",
                            fontSize = 22.sp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .background(
                                    color  = Color.Black.copy(alpha = 0.55f),
                                    shape  = RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            screenTracks.forEach { (pid, track) ->
                Text("Screen share: ${participantName(participants, pid)}", style = MaterialTheme.typography.labelMedium)
                VideoRenderer(track = track, eglContext = egl, modifier = Modifier.fillMaxWidth().height(220.dp))
            }
            remoteTracks.forEach { (pid, track) ->
                val muteState = peerMuteStates[pid]
                Text(
                    text = participantName(participants, pid) +
                        (if (activeSpeakerId == pid) " 🔊" else "") +
                        (if (muteState?.isAudioMuted == true) " 🔇" else "") +
                        (if (muteState?.isVideoMuted == true) " 📷" else "") +
                        // P2P only — direct connection to this peer hard-failed.
                        (if (pid in peerConnectionFailed) " ⚠️" else ""),
                    style = MaterialTheme.typography.labelMedium,
                )
                VideoRenderer(track = track, eglContext = egl, modifier = Modifier.fillMaxWidth().height(200.dp))
            }
        }

        if (isRoomLocked) {
            Text("🔒 Room is currently locked", color = MaterialTheme.colorScheme.error)
        }
        // Only ever populated for EVENT-type rooms — see viewerCount's doc comment.
        if (viewerCount != null) {
            Text("👁 $viewerCount watching", style = MaterialTheme.typography.labelMedium)
        }
        if (isReconnecting) {
            // ConnectionState.Failed fired mid-call — SignallingClient is retrying with its
            // own exponential backoff (see RoomViewModel's connectionState.collect). This
            // clears on ConnectionState.Reconnected, or the call ends via the terminal
            // SOCKET_RECONNECT_FAILED error if all retries are exhausted.
            Text("🔄 Reconnecting…", color = MaterialTheme.colorScheme.error)
        }
        if (isMigratingToSfu) {
            // P2P room auto-upgrading to a group (SFU) call — server-driven once the
            // room crosses its p2pThreshold peer count. Clears on RoomModeChanged.
            Text("⬆️ Upgrading to group call…", color = MaterialTheme.colorScheme.error)
        }

        // ── Media controls ────────────────────────────────────────────────────
        // Gated on roomExists — mirrors RN's `{sdkInstanceRef.current && (<View>...)}`.
        // Unlike the sections below, RN does hide this whole block until a room/SDK
        // instance exists, rather than leaving it visible-but-inert.
        if (roomExists) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.toggleMic() }) { Text(if (isMicMuted) "Unmute" else "Mute") }
                OutlinedButton(onClick = { vm.toggleCamera() }) { Text(if (isCameraOff) "Camera On" else "Camera Off") }
                OutlinedButton(onClick = { vm.flipCamera() }) { Text("Flip Camera") }
                if (videoDevices.size > 2) {
                    OutlinedButton(onClick = { showVideoDevicePicker = true }) { Text("Select Camera") }
                }
                OutlinedButton(onClick = {
                    if (isScreenSharing) {
                        vm.stopScreenShare()
                    } else {
                        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenShareLauncher.launch(mgr.createScreenCaptureIntent())
                    }
                }) { Text(if (isScreenSharing) "Stop Share" else "Share Screen") }
                if (isModerator) {
                    OutlinedButton(onClick = { vm.toggleRecording() }) { Text(if (isRecording) "Stop REC" else "Start REC") }
                    OutlinedButton(onClick = { vm.muteAllParticipants() }) { Text("Mute All") }
                }
            }
        }
        if (isRecording) {
            Text("● Recording in Progress", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }

        // ── Engagement ─────────────────────────────────────────────────────────
        Text("Engagement", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.toggleHand() }) { Text(if (handRaised) "Lower Hand" else "Raise Hand") }
            if (peerType == RoomConstants.PEER_VIEWER) {
                OutlinedButton(onClick = { vm.requestUpgrade() }) { Text("Request Upgrade") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ReactionEmojis.forEach { emoji ->
                TextButton(onClick = { vm.sendReaction(emoji) }) { Text(emoji, fontSize = 18.sp) }
            }
        }
        if (recentReactions.isNotEmpty()) {
            Column {
                recentReactions.takeLast(6).reversed().forEach { r ->
                    Text("${r.fromName}: ${r.emoji}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ── Hands Raised (moderator) ──────────────────────────────────────────
        if (isModerator && handRaiseList.isNotEmpty()) {
            SectionDivider("Hands Raised")
            handRaiseList.forEach { entry ->
                NoticeRow(
                    title = "✋ ${entry.peerName ?: entry.peerId.take(8)}" + if (entry.upgradeRequest) " (wants to present)" else "",
                ) {
                    TextButton(onClick = { vm.upgradePeer(entry.peerId) }) { Text("Upgrade") }
                    TextButton(onClick = { vm.lowerPeerHand(entry.peerId) }) { Text("Lower") }
                }
            }
        }

        // ── Incoming upgrade invite ────────────────────────────────────────────
        if (incomingUpgradeInvite != null) {
            SectionDivider("Moderator Invite")
            Text("The moderator wants to upgrade you to presenter.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.acceptUpgrade() }) { Text("Accept") }
                OutlinedButton(onClick = { vm.rejectUpgrade() }) { Text("Decline") }
            }
        }

        // ── Chat ───────────────────────────────────────────────────────────────
        SectionDivider("Chat")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text  = "Sending to: " + (selectedChatReceiver?.let { participantName(participants, it) } ?: "Everyone"),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { showChatTargetPicker = true }) { Text("Change") }
        }
        if (chatMessages.isEmpty()) {
            Text("No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                chatMessages.takeLast(50).forEach { msg ->
                    val prefix = if (msg.isLocal) "You" else msg.senderName
                    val privateTag = if (msg.scope == RoomConstants.MSG_TYPE_PRIVATE) " (Private)" else ""
                    Text("$prefix$privateTag: ${msg.text}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value         = chatInput,
                onValueChange = { chatInput = it },
                placeholder   = { Text("Type message") },
                singleLine    = true,
                modifier      = Modifier.weight(1f),
            )
            Button(onClick = { vm.sendChat(chatInput); chatInput = "" }) { Text("Send") }
        }

        // ── Stage Routing ──────────────────────────────────────────────────────
        SectionDivider("Stage Routing")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.goOnStage() }) { Text("Go On Stage") }
            OutlinedButton(onClick = { vm.backStage() }) { Text("Back Stage") }
        }
        Text(if (isOnStage) "You are on stage." else "You are back stage.", style = MaterialTheme.typography.bodySmall)
        if (isModerator) {
            OutlinedTextField(
                value         = stagePeersInput,
                onValueChange = { stagePeersInput = it },
                placeholder   = { Text("Stage Peer IDs (comma separated)") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value         = backStagePeersInput,
                onValueChange = { backStagePeersInput = it },
                placeholder   = { Text("Back Stage Peer IDs (comma separated)") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                val stage = stagePeersInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val backStage = backStagePeersInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (stage.isNotEmpty()) vm.setStagePeers(stage)
                if (backStage.isNotEmpty()) vm.removeStagePeers(backStage)
            }) { Text("Apply Stage Settings") }
        }

        // ── Room settings (moderator only) ────────────────────────────────────
        if (isModerator) {
            SectionDivider("General Permissions")
            ToggleRow("Allow Screen Share", allowScreenShare) { allowScreenShare = it }
            OutlinedTextField(
                value = noOfScreenShare, onValueChange = { noOfScreenShare = it },
                placeholder = { Text("Max Screen Shares") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = noOfUpgradeReq, onValueChange = { noOfUpgradeReq = it },
                placeholder = { Text("Max Upgrade Requests") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                vm.applyGeneralSettings(allowScreenShare, noOfScreenShare.toIntOrNull() ?: 0, noOfUpgradeReq.toIntOrNull() ?: 0)
            }) { Text("Save General") }

            SectionDivider("Presenter Permissions")
            ToggleRow("allowPresenterRaiseHand", allowPresenterRaiseHand) { allowPresenterRaiseHand = it }
            ToggleRow("allowPresenterPublicChat", allowPresenterPublicChat) { allowPresenterPublicChat = it }
            ToggleRow("allowPresenterPrivateChat", allowPresenterPrivateChat) { allowPresenterPrivateChat = it }
            Button(onClick = { vm.applyPresenterSettings(allowPresenterRaiseHand, allowPresenterPublicChat, allowPresenterPrivateChat) }) {
                Text("Save Presenter")
            }

            SectionDivider("Participant Permissions")
            ToggleRow("allowParticipantRaiseHand", allowParticipantRaiseHand) { allowParticipantRaiseHand = it }
            ToggleRow("allowParticipantPublicChat", allowParticipantPublicChat) { allowParticipantPublicChat = it }
            ToggleRow("allowParticipantPrivateChat", allowParticipantPrivateChat) { allowParticipantPrivateChat = it }
            Button(onClick = { vm.applyParticipantSettings(allowParticipantRaiseHand, allowParticipantPublicChat, allowParticipantPrivateChat) }) {
                Text("Save Participants")
            }
        }

        // ── Transcription & Live Streaming ────────────────────────────────────
        if (isModerator) {
            SectionDivider("Transcription & Live Streaming")
            OutlinedButton(onClick = {
                if (transcriptionActive) vm.stopTranscription() else vm.startTranscription()
            }) { Text(if (transcriptionActive) "Stop Transcription" else "Start Transcription") }

            if (isLiveStreaming) {
                Button(
                    onClick  = { vm.stopLiveStream() },
                    colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop Stream") }
            } else {
                OutlinedTextField(
                    value = liveStreamUrl, onValueChange = { liveStreamUrl = it },
                    placeholder = { Text("RTMP URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = liveStreamKey, onValueChange = { liveStreamKey = it },
                    placeholder = { Text("Stream Key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick  = { vm.startLiveStream(liveStreamUrl.trim(), liveStreamKey.trim()) },
                    enabled  = liveStreamUrl.isNotBlank() && liveStreamKey.isNotBlank(),
                ) { Text("Start Stream") }
            }
        }
        if (transcriptionActive && transcriptionEntries.isNotEmpty()) {
            Column {
                transcriptionEntries.takeLast(5).forEach { e ->
                    Text(
                        text  = if (e.speaker != null) "${e.speaker}: ${e.transcript}" else e.transcript,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (e.isFinal) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── AI Assistant ───────────────────────────────────────────────────────
        SectionDivider("AI Assistant")
        if (isModerator) {
            Text(
                text  = "Select streams to analyse (by participant)",
                style = MaterialTheme.typography.bodySmall,
            )
            if (aiSelectableStreams.isEmpty()) {
                Text(
                    "No publishing streams available",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                // Grouped by peer, not by individual stream — startAiAgent() only
                // accepts peer IDs, not per-mediaTag targets (see toggleAiPeerTick's
                // doc comment in RoomViewModel).
                aiSelectableStreams.map { it.peerId }.distinct().forEach { pid ->
                    val tags = aiSelectableStreams.filter { it.peerId == pid }.joinToString { it.mediaTag }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = pid in aiTickedPeers, onCheckedChange = { vm.toggleAiPeerTick(pid) })
                        Text("${participantName(participants, pid)} — $tags", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.loadAiSelectableStreams() }) { Text("Refresh") }
                Button(onClick = { vm.applyAiSelection() }) {
                    Text(if (aiTickedPeers.isEmpty()) "Stop AI Agent" else "Apply Selection")
                }
            }
            if (isAiAgentActive) {
                Text("● AI agent is active", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
        // Everyone's own streams — independent of the moderator's picker above.
        // canGrant/canRevoke come straight from the server (AiMyConsentStream), never
        // inferred locally, since only the server knows why a stream is in its current state.
        if (myAiStreams.isNotEmpty()) {
            Text("Your streams", style = MaterialTheme.typography.bodySmall)
            myAiStreams.forEach { s ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${s.mediaTag} — ${s.state ?: "not asked yet"}",
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (s.canGrant) TextButton(onClick = { vm.grantAiConsent(s.mediaTag) }) { Text("Allow") }
                    if (s.canRevoke) TextButton(onClick = { vm.revokeAiConsent(s.mediaTag) }) { Text("Stop") }
                }
            }
        }
        if (aiConsentError != null) {
            Text(aiConsentError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        // Live observations feed — everyone. Deliberately doesn't render AiTranscript;
        // relaying transcripts is a separate, per-deployment privacy decision.
        if (aiObservations.isNotEmpty()) {
            Text("Observations", style = MaterialTheme.typography.bodySmall)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                aiObservations.takeLast(20).reversed().forEach { obs ->
                    Text(
                        text  = "${participantName(participants, obs.senderPeerId)} (${obs.source ?: "?"}): ${obs.payload}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ── Waiting Room (moderator) ──────────────────────────────────────────
        if (isModerator && waitingPeers.isNotEmpty()) {
            SectionDivider("Waiting Room")
            waitingPeers.forEach { peer ->
                NoticeRow(title = peer.peerName ?: peer.peerId.take(8)) {
                    TextButton(onClick = { vm.admitPeer(peer.peerId) }) { Text("Admit") }
                    TextButton(onClick = { vm.denyPeer(peer.peerId) }) { Text("Reject") }
                }
            }
        }

        // ── Upgrade Requests (moderator) ──────────────────────────────────────
        if (isModerator && pendingUpgradeRequests.isNotEmpty()) {
            SectionDivider("Upgrade Requests")
            pendingUpgradeRequests.forEach { req ->
                NoticeRow(title = "${req.peerName ?: req.peerId.take(8)} — invited") {
                    TextButton(onClick = { vm.cancelUpgradeInvite(req.peerId) }) { Text("Cancel") }
                }
            }
        }

        // ── Participants ───────────────────────────────────────────────────────
        // Whole section hidden when empty — mirrors RN's `{peersArray.length > 0 && (...)}`.
        // (Screen shares that exist are already rendered in the video area above; RN's
        // own "Screen Shares" section is likewise just its content with no empty-state
        // placeholder, so there's nothing to duplicate here when screenTracks is empty.)
        if (participants.isNotEmpty()) {
            SectionDivider("Participants (${participants.size})")
            participants.forEach { peer ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    val muteState = peerMuteStates[peer.peerId]
                    Text(
                        text = (peer.name ?: peer.peerId.take(8)) +
                            (if (peer.isLocal) " (you)" else "") +
                            (if (muteState?.isAudioMuted == true) " 🔇" else "") +
                            (if (muteState?.isVideoMuted == true) " 📷" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!peer.isLocal && peer.participantType != null) {
                        Text(peer.participantType!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!peer.isLocal && isModerator) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { vm.kickPeer(peer.peerId) }) { Text("Kick") }
                            if (peer.participantType == RoomConstants.PEER_VIEWER) {
                                TextButton(onClick = { vm.upgradePeer(peer.peerId) }) { Text("Upgrade") }
                                TextButton(onClick = { vm.sendUpgradeInvite(peer.peerId) }) { Text("Request") }
                            }
                            if (peer.participantType == RoomConstants.PEER_PARTICIPANT) {
                                TextButton(onClick = { vm.downgradePeer(peer.peerId) }) { Text("Downgrade") }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        // Disabled (not hidden) when no room exists yet — mirrors RN's Leave Room button,
        // which is `disabled={!sdkInstanceRef.current}` rather than conditionally rendered.
        OutlinedButton(
            onClick  = { vm.leaveRoom() },
            enabled  = roomExists,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Leave Room") }
        if (isModerator) {
            Button(
                onClick  = { vm.closeRoom() },
                enabled  = roomExists,
                colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Close Room for Everyone") }
        }
    }

    // ── Modals ─────────────────────────────────────────────────────────────────

    if (showVideoDevicePicker) {
        AlertDialog(
            onDismissRequest = { showVideoDevicePicker = false },
            confirmButton    = { TextButton(onClick = { showVideoDevicePicker = false }) { Text("Close") } },
            title = { Text("Select Camera") },
            text  = {
                Column {
                    videoDevices.forEach { device ->
                        TextButton(
                            onClick = { vm.selectVideoDevice(device); showVideoDevicePicker = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(device.label, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
        )
    }

    // Driven entirely by pendingAiConsent state, not a one-shot showDialog() call — a
    // real bug (fixed on the Flutter side, see SDK_UPDATE_FIXES.md) was a fire-and-forget
    // dialog that never closed because nothing was actually watching the server's clock.
    // State-driven means this recomposes/disappears automatically the moment
    // pendingAiConsent changes, including on a server-side aiConsentExpired.
    val consentRequest = pendingAiConsent
    if (consentRequest != null) {
        // Keyed on consentRequest so a NEW request never inherits ticks left over
        // from a previous one — matches Flutter's _aiConsentChecked.clear() on
        // every fresh AiConsentRequestEvent.
        var checkedTags by remember(consentRequest) { mutableStateOf(emptySet<String>()) }
        AlertDialog(
            onDismissRequest = { /* force an explicit Allow/Decline — no swipe-to-dismiss */ },
            title = { Text("Allow the AI assistant?") },
            text = {
                Column {
                    Text(
                        "A moderator has asked the AI assistant to analyse the following. " +
                            "Choose what you're comfortable sharing — nothing is analysed unless you allow it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Default UNCHECKED — consent is opt-in; a pre-ticked box isn't consent.
                    consentRequest.streams.forEach { stream ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = stream.mediaTag in checkedTags,
                                onCheckedChange = { checked ->
                                    checkedTags = if (checked) checkedTags + stream.mediaTag else checkedTags - stream.mediaTag
                                },
                            )
                            Text(stream.mediaTag)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.respondAiConsent(checkedTags) }) { Text("Allow selected") } },
            dismissButton = { TextButton(onClick = { vm.respondAiConsent(false) }) { Text("Decline") } },
        )
    }

    if (showChatTargetPicker) {
        AlertDialog(
            onDismissRequest = { showChatTargetPicker = false },
            confirmButton    = { TextButton(onClick = { showChatTargetPicker = false }) { Text("Close") } },
            title = { Text("Send chat to") },
            text  = {
                Column {
                    TextButton(
                        onClick = { vm.setPrivateChatTarget(null); showChatTargetPicker = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Everyone", modifier = Modifier.fillMaxWidth()) }
                    participants.filterNot { it.isLocal }.forEach { peer ->
                        TextButton(
                            onClick = { vm.setPrivateChatTarget(peer.peerId); showChatTargetPicker = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(peer.name ?: peer.peerId.take(8), modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
        )
    }
}

// ── Small helpers ──────────────────────────────────────────────────────────────

@Composable
private fun SectionDivider(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun NoticeRow(title: String, actions: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Row { actions() }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun participantName(
    participants: List<io.samvyo.sdk.Participant>,
    peerId: String,
): String = participants.firstOrNull { it.peerId == peerId }?.name ?: peerId.take(8)
