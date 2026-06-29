package io.samvyo.demo

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.samvyo.demo.ui.VideoCallScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RoomScreen()
                }
            }
        }
    }
}

@Composable
fun RoomScreen(vm: RoomViewModel = viewModel()) {
    val state by vm.ui.collectAsState()

    // When in a live call show the full-screen video UI and return early —
    // none of the form widgets below should be visible during a call.
    if (state is RoomUiState.InCall) {
        val inCall = state as RoomUiState.InCall
        val isMicMuted by vm.isMicMuted.collectAsState()
        val isCameraOff by vm.isCameraOff.collectAsState()
        VideoCallScreen(
            localVideoTrack  = inCall.localVideoTrack,
            remoteVideoTracks = inCall.remoteVideoTracks,
            eglContext       = vm.eglBase.eglBaseContext,
            isMicMuted       = isMicMuted,
            isCameraOff      = isCameraOff,
            onToggleMic      = { vm.toggleMic() },
            onToggleCamera   = { vm.toggleCamera() },
            onLeave          = { vm.leaveRoom() },
        )
        return
    }

    val log by vm.log.collectAsState()
    var roomId   by remember { mutableStateOf("") }
    var peerName by remember { mutableStateOf("peer-1") }
    val editable = state is RoomUiState.Idle || state is RoomUiState.Error

    // Permission launcher: requests CAMERA + RECORD_AUDIO together.
    // If both granted → join room. If any denied → show error.
    // We ask at this moment (not at app start) because Android 6+ requires
    // dangerous permissions to be requested at the point of use.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) vm.joinRoom()
        else vm.onPermissionsDenied()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Samvyo Video Call", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = roomId,
            onValueChange = { roomId = it },
            label = { Text("Room ID") },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = peerName,
            onValueChange = { peerName = it },
            label = { Text("Your Name") },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )

        when (state) {
            is RoomUiState.Idle, is RoomUiState.Error ->
                Button(
                    onClick = { vm.initRoom(roomId.trim(), peerName.trim()) },
                    enabled = roomId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Init Room") }

            is RoomUiState.Initializing ->
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Initializing…")
                }

            is RoomUiState.Ready -> {
                Button(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Join Room") }
                OutlinedButton(onClick = { vm.leaveRoom() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Leave")
                }
            }

            is RoomUiState.Joining ->
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Joining…")
                }

            is RoomUiState.Joined -> {
                val joined = state as RoomUiState.Joined
                Text("Room: ${joined.roomDisplayName ?: "(unnamed)"}")
                Text("Participants (${joined.participants.size}):",
                    style = MaterialTheme.typography.titleSmall)
                joined.participants.forEach { p ->
                    Text(
                        "• ${p.name ?: p.peerId}${if (p.isLocal) " (you)" else ""}" +
                            (p.participantType?.let { " — $it" } ?: ""),
                        fontSize = 13.sp,
                    )
                }
                Text("Starting call…", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { vm.leaveRoom() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Leave Room")
                }
            }

            is RoomUiState.InCall -> {}
        }

        Text(
            text = "State: ${state.label()}",
            color = if (state is RoomUiState.Error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )

        Text("WebSocket log", style = MaterialTheme.typography.titleSmall)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (log.isEmpty()) {
                Text("(no activity yet)", color = MaterialTheme.colorScheme.outline)
            } else {
                log.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun RoomUiState.label(): String = when (this) {
    is RoomUiState.Idle         -> "Idle"
    is RoomUiState.Initializing -> "Initializing"
    is RoomUiState.Ready        -> "Ready (socket connected)"
    is RoomUiState.Joining      -> "Joining"
    is RoomUiState.Joined       -> "Joined (${participants.size} in room)"
    is RoomUiState.InCall       -> "In Call"
    is RoomUiState.Error        -> "Error: $message"
}
