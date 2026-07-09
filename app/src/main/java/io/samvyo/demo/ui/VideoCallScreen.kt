package io.samvyo.demo.ui

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Everything that appears once a call is joined, laid out as one continuous set of
 * sections — mirrors the RN reference app's structure (App.js renders join controls
 * and call controls in a single ScrollView, not as separate screens). MainActivity's
 * RoomScreen embeds this directly inside its own scrollable Column when state is InCall,
 * rather than switching to a full-screen replacement.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallSections(vm: RoomViewModel) {

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
    val localPeerType    by vm.localPeerType.collectAsState()
    val isModerator      = localPeerType == RoomConstants.PEER_MODERATOR
    val isRoomLocked      by vm.isRoomLocked.collectAsState()
    val handRaiseList     by vm.handRaiseList.collectAsState()
    val recentReactions   by vm.recentReactions.collectAsState()
    val selectedChatReceiver by vm.selectedChatReceiver.collectAsState()
    val pendingUpgradeRequests by vm.pendingUpgradeRequests.collectAsState()
    val incomingUpgradeInvite  by vm.incomingUpgradeInvite.collectAsState()
    val waitingPeers            by vm.waitingPeers.collectAsState()
    val isOnStage               by vm.isOnStage.collectAsState()

    val videoDevices = remember { vm.listVideoDevices() }

    var showChatTargetPicker  by remember { mutableStateOf(false) }
    var showVideoDevicePicker by remember { mutableStateOf(false) }
    var chatInput by remember { mutableStateOf("") }
    var stagePeersInput     by remember { mutableStateOf("") }
    var backStagePeersInput by remember { mutableStateOf("") }
    var maxOnStageInput by remember { mutableStateOf("") }
    var liveStreamUrl by remember { mutableStateOf("") }
    var liveStreamKey by remember { mutableStateOf("") }

    // Local-only permission form state — same pattern as the RN app: these are just the
    // values the moderator is about to Save, not a live sync of the room's actual settings
    // (Android has no confirmation event for these, see RoomViewModel's Room settings section).
    var allowScreenShare  by remember { mutableStateOf(true) }
    var noOfScreenShare   by remember { mutableStateOf("1") }
    var noOfUpgradeReq    by remember { mutableStateOf("5") }
    var allowPresenterMic by remember { mutableStateOf(true) }
    var allowPresenterCam by remember { mutableStateOf(true) }
    var allowPresenterShare by remember { mutableStateOf(true) }
    var allowParticipantChat by remember { mutableStateOf(true) }
    var allowParticipantHandRaise by remember { mutableStateOf(true) }

    val context = LocalContext.current
    val screenShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            vm.startScreenShare(result.resultCode, result.data!!)
        }
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
                Text("You", style = MaterialTheme.typography.labelMedium)
                VideoRenderer(
                    track      = localTrack!!,
                    eglContext = egl,
                    mirror     = true,
                    modifier   = Modifier.fillMaxWidth().height(200.dp),
                )
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
                        (if (muteState?.isVideoMuted == true) " 📷" else ""),
                    style = MaterialTheme.typography.labelMedium,
                )
                VideoRenderer(track = track, eglContext = egl, modifier = Modifier.fillMaxWidth().height(200.dp))
            }
            if (remoteTracks.isEmpty() && screenTracks.isEmpty()) {
                Text("Waiting for others to join…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (isRoomLocked) {
            Text("🔒 Room is currently locked", color = MaterialTheme.colorScheme.error)
        }

        // ── Media controls ────────────────────────────────────────────────────
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
            }
        }
        if (isRecording) {
            Text("● Recording in Progress", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }

        // ── Engagement ─────────────────────────────────────────────────────────
        Text("Engagement", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.toggleHand() }) { Text(if (handRaised) "Lower Hand" else "Raise Hand") }
            if (localPeerType == RoomConstants.PEER_VIEWER) {
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
            OutlinedTextField(
                value         = maxOnStageInput,
                onValueChange = { maxOnStageInput = it },
                placeholder   = { Text("Max On Stage") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { vm.updateStageSettings(maxOnStageInput.toIntOrNull(), null) }) {
                Text("Save Stage Capacity")
            }
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
            ToggleRow("allowMic", allowPresenterMic) { allowPresenterMic = it }
            ToggleRow("allowCamera", allowPresenterCam) { allowPresenterCam = it }
            ToggleRow("allowScreenShare", allowPresenterShare) { allowPresenterShare = it }
            Button(onClick = { vm.applyPresenterSettings(allowPresenterMic, allowPresenterCam, allowPresenterShare) }) {
                Text("Save Presenter")
            }

            SectionDivider("Participant Permissions")
            ToggleRow("allowChat", allowParticipantChat) { allowParticipantChat = it }
            ToggleRow("allowHandRaise", allowParticipantHandRaise) { allowParticipantHandRaise = it }
            Button(onClick = { vm.applyParticipantSettings(allowParticipantChat, allowParticipantHandRaise) }) {
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

        // ── Screen Shares ──────────────────────────────────────────────────────
        if (screenTracks.isEmpty()) {
            SectionDivider("Screen Shares")
            Text("No Screen Share", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        // ── Participants ───────────────────────────────────────────────────────
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

        HorizontalDivider()
        OutlinedButton(onClick = { vm.leaveRoom() }, modifier = Modifier.fillMaxWidth()) { Text("Leave Room") }
        if (isModerator) {
            Button(
                onClick  = { vm.closeRoom() },
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
