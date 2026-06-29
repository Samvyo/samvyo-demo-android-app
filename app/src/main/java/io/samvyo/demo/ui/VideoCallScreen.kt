package io.samvyo.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.webrtc.EglBase
import org.webrtc.VideoTrack

// Full-screen video call UI.
//
// Box layers (back → front):
//   1. Remote video fills the whole background.
//   2. Local camera preview in the top-right corner (small, mirrored).
//   3. Control bar at the bottom — Mute | Leave | Camera.
//
// Box stacks children: the last child is painted on top.
@Composable
fun VideoCallScreen(
    localVideoTrack: VideoTrack?,
    remoteVideoTracks: Map<String, VideoTrack>,
    eglContext: EglBase.Context,
    isMicMuted: Boolean,
    isCameraOff: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onLeave: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Layer 1: Remote video — fills the whole screen.
        when {
            remoteVideoTracks.isEmpty() -> {
                Text(
                    text = "Waiting for others to join…",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            remoteVideoTracks.size == 1 -> {
                VideoRenderer(
                    track = remoteVideoTracks.values.first(),
                    eglContext = eglContext,
                    modifier = Modifier.fillMaxSize(),
                    mirror = false,
                )
            }
            else -> {
                // Multiple remote peers — show side by side in a horizontal scroll.
                LazyRow(modifier = Modifier.fillMaxSize()) {
                    items(remoteVideoTracks.values.toList()) { track ->
                        VideoRenderer(
                            track = track,
                            eglContext = eglContext,
                            modifier = Modifier
                                .fillParentMaxHeight()
                                .fillParentMaxWidth(fraction = 0.5f),
                            mirror = false,
                        )
                    }
                }
            }
        }

        // Layer 2: Local camera preview — top-right corner.
        // mirror=true: front camera is naturally mirrored (selfie convention).
        // zOrderMediaOverlay=true: lifts this Surface above the remote video Surface in the compositor.
        if (localVideoTrack != null) {
            VideoRenderer(
                track = localVideoTrack,
                eglContext = eglContext,
                mirror = true,
                zOrderMediaOverlay = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(width = 100.dp, height = 140.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }

        // Layer 3: Control bar — bottom centre.
        // Grey = feature ON. Red = feature OFF or danger action (leave).
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mic button
            IconButton(
                onClick = onToggleMic,
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (isMicMuted) Color.Red else Color(0xFF555555),
                ),
            ) {
                Icon(
                    painter = painterResource(
                        if (isMicMuted) android.R.drawable.ic_lock_silent_mode
                        else android.R.drawable.ic_btn_speak_now
                    ),
                    contentDescription = if (isMicMuted) "Unmute" else "Mute",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }

            // Leave button — always red, slightly larger so it is easy to tap
            IconButton(
                onClick = onLeave,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Red),
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_call),
                    contentDescription = "Leave call",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }

            // Camera button
            IconButton(
                onClick = onToggleCamera,
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (isCameraOff) Color.Red else Color(0xFF555555),
                ),
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_camera),
                    contentDescription = if (isCameraOff) "Turn camera on" else "Turn camera off",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
