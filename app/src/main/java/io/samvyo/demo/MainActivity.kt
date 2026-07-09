package io.samvyo.demo

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.samvyo.demo.ui.CallSections
import io.samvyo.sdk.RoomConstants

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoomScreen(vm: RoomViewModel = viewModel()) {
    val state by vm.ui.collectAsState()

    // ── Join screen ────────────────────────────────────────────────────────────
    // Matches the RN reference app's structure: join controls and call controls live
    // in the same continuously scrolling screen (not a screen switch) — the fields
    // below simply become non-editable/hidden as appropriate once state is InCall,
    // and CallSections(vm) is appended at the bottom of this same Column.

    var roomId    by rememberSaveable { mutableStateOf("") }
    var peerName  by rememberSaveable { mutableStateOf("peer-1") }
    var peerType  by rememberSaveable { mutableStateOf(RoomConstants.PEER_PARTICIPANT) }
    var roomType  by rememberSaveable { mutableStateOf(RoomConstants.ROOM_CONFERENCING) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var produce   by rememberSaveable { mutableStateOf(true) }
    var consume   by rememberSaveable { mutableStateOf(true) }
    var enableScreenSharing by rememberSaveable { mutableStateOf(true) }
    var selectedCameraId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDevicePicker by remember { mutableStateOf(false) }

    val log   by vm.log.collectAsState()
    val editable = state is RoomUiState.Idle || state is RoomUiState.Error

    // Camera list is queryable before a room exists (Camera2Enumerator is a static
    // Android API, not SDK/room state) — matches the RN app's pre-join "Select Devices"
    // button. Applying the choice still has to wait until local video actually starts
    // (see RoomViewModel.setPreferredCamera()'s doc comment), so this button only
    // *records* a preference now; VideoStart applies it once a room exists.
    val videoDevices = remember { vm.listVideoDevices() }

    // Permissions to request at join time:
    //   - CAMERA + RECORD_AUDIO (always)
    //   - POST_NOTIFICATIONS (Android 13+ for screen-share notification)
    val permissionsToRequest = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Camera + mic are required. POST_NOTIFICATIONS is best-effort.
        val cameraGranted = results[Manifest.permission.CAMERA] == true
        val audioGranted  = results[Manifest.permission.RECORD_AUDIO] == true
        if (cameraGranted && audioGranted) {
            vm.joinRoom(
                peerType            = peerType,
                produce             = produce,
                consume             = consume,
                enableScreenSharing = enableScreenSharing,
            )
        } else {
            vm.onPermissionsDenied()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Samvyo Demo", style = MaterialTheme.typography.headlineMedium)

        // Room ID + peer name inputs
        OutlinedTextField(
            value         = roomId,
            onValueChange = { roomId = it },
            label         = { Text("Room ID") },
            singleLine    = true,
            enabled       = editable,
            modifier      = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value         = peerName,
            onValueChange = { peerName = it },
            label         = { Text("Your Name") },
            singleLine    = true,
            enabled       = editable,
            modifier      = Modifier.fillMaxWidth(),
        )

        // ── Room type selector ────────────────────────────────────────────────
        // Comes before "Join as" (not after) because the role labels below depend on
        // it — matches the RN reference app's field order, so users pick the room
        // shape before picking a role name that only makes sense within that shape.
        Text("Room type", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                RoomConstants.ROOM_CONFERENCING to "Conferencing",
                RoomConstants.ROOM_EVENT        to "Event",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = roomType == value,
                    onClick  = { if (editable) roomType = value },
                    label    = { Text(label) },
                )
            }
        }

        // ── Peer type selector ────────────────────────────────────────────────
        // Event rooms use different terminology for the same wire values:
        // PEER_PARTICIPANT -> "Presenter", PEER_VIEWER -> "Attendee".
        Text("Join as", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val isEvent = roomType == RoomConstants.ROOM_EVENT
            listOf(
                RoomConstants.PEER_MODERATOR   to "Moderator",
                RoomConstants.PEER_PARTICIPANT to if (isEvent) "Presenter" else "Participant",
                RoomConstants.PEER_VIEWER      to if (isEvent) "Attendee" else "Viewer",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = peerType == value,
                    onClick  = { if (editable) peerType = value },
                    label    = { Text(label) },
                )
            }
        }
        // Explains what the picked role can actually do — same copy/condition as the
        // RN reference app's helper text under its role selector.
        Text(
            text  = if (peerType == RoomConstants.PEER_VIEWER) {
                "Viewers/attendees join in view-only mode."
            } else {
                "Moderators and presenters/participants can publish audio/video."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Device selection — video only, see the videoDevices comment above for why
        // there's no audio counterpart (SDK gap: changeAudioInput() takes no device id). ─
        if (videoDevices.size > 1) {
            OutlinedButton(
                onClick  = { showDevicePicker = true },
                enabled  = editable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val label = videoDevices.firstOrNull { it.id == selectedCameraId }?.label
                Text(if (label != null) "Camera: $label" else "Select Camera")
            }
        }
        if (showDevicePicker) {
            AlertDialog(
                onDismissRequest = { showDevicePicker = false },
                confirmButton    = {
                    TextButton(onClick = { showDevicePicker = false }) { Text("Close") }
                },
                title = { Text("Select Camera") },
                text  = {
                    Column {
                        videoDevices.forEach { device ->
                            TextButton(
                                onClick = {
                                    selectedCameraId = device.id
                                    vm.setPreferredCamera(device)
                                    showDevicePicker = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(device.label, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
            )
        }

        // ── Advanced options (collapsible) ────────────────────────────────────
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Hide advanced options" else "Show advanced options")
        }
        AnimatedVisibility(visible = showAdvanced) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Text("Advanced options", style = MaterialTheme.typography.labelLarge)
                // FlowRow (not Row) so a chip wraps to its own line instead of being
                // squeezed narrower than its label — that's what was clipping
                // "Consume (receive media)" onto three lines inside a fixed-width chip.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = produce,
                        onClick  = { if (editable) produce = !produce },
                        label    = { Text("Produce (send media)") },
                    )
                    FilterChip(
                        selected = consume,
                        onClick  = { if (editable) consume = !consume },
                        label    = { Text("Consume (receive media)") },
                    )
                }
                FilterChip(
                    selected = enableScreenSharing,
                    onClick  = { if (editable) enableScreenSharing = !enableScreenSharing },
                    label    = { Text("Enable screen sharing") },
                )
                HorizontalDivider()
            }
        }

        // ── Action buttons ────────────────────────────────────────────────────
        when (state) {
            is RoomUiState.Idle, is RoomUiState.Error ->
                Button(
                    onClick  = { vm.initRoom(roomId.trim(), peerName.trim(), roomType) },
                    enabled  = roomId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Init Room") }

            is RoomUiState.Connecting ->
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Connecting…")
                }

            is RoomUiState.Ready -> {
                Button(
                    onClick = {
                        permissionLauncher.launch(permissionsToRequest)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Join Room") }
                OutlinedButton(
                    onClick  = { vm.leaveRoom() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            }

            is RoomUiState.Joining ->
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Joining…")
                }

            is RoomUiState.InCall -> {}
        }

        // Error message
        if (state is RoomUiState.Error) {
            Text(
                text  = (state as RoomUiState.Error).message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Status line
        Text(
            text  = "State: ${state.label()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Call sections — appended below the join controls once InCall, same screen. ──
        if (state is RoomUiState.InCall) {
            CallSections(vm)
        }

        // WebSocket debug log
        HorizontalDivider()
        Text("Socket log", style = MaterialTheme.typography.titleSmall)
        Column(modifier = Modifier.fillMaxWidth()) {
            if (log.isEmpty()) {
                Text("(no activity yet)", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
            } else {
                log.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun RoomUiState.label(): String = when (this) {
    is RoomUiState.Idle       -> "Idle"
    is RoomUiState.Connecting -> "Connecting…"
    is RoomUiState.Ready      -> "Ready (tap Join Room)"
    is RoomUiState.Joining    -> "Joining…"
    is RoomUiState.InCall     -> "In Call"
    is RoomUiState.Error      -> "Error"
}
