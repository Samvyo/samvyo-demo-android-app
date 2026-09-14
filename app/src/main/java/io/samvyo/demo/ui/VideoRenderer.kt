package io.samvyo.demo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

// Renders a single WebRTC VideoTrack onto a SurfaceViewRenderer inside Compose.
//
// Two separate DisposableEffects:
//   Effect 1 (key = Unit)  — runs once: initialises / releases the renderer.
//   Effect 2 (key = track) — re-runs if the track changes: attaches / detaches the sink.
//   Keeping them separate prevents renderer.init() being called twice when only the track swaps.
//
// zOrderMediaOverlay:
//   SurfaceViewRenderer is a SurfaceView — it draws directly to a compositor layer,
//   outside the normal Compose/View drawing tree.
//   Two SurfaceViews on screen share the same compositor Z-layer by default, so the
//   remote video surface can paint OVER the local preview even if Compose placed it "above".
//   setZOrderMediaOverlay(true) promotes the local preview to a higher layer → always visible.
@Composable
fun VideoRenderer(
    track: VideoTrack,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    zOrderMediaOverlay: Boolean = false,
) {
    val context = LocalContext.current
    val renderer = remember(context) { SurfaceViewRenderer(context) }

    // Effect 1: renderer lifecycle — runs once for the lifetime of this Composable.
    DisposableEffect(Unit) {
        renderer.init(eglContext, null)
        renderer.setMirror(mirror)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        if (zOrderMediaOverlay) renderer.setZOrderMediaOverlay(true)
        onDispose { renderer.release() }
    }

    // Effect 2: track attachment — re-runs if the VideoTrack object changes.
    DisposableEffect(track) {
        track.addSink(renderer)
        onDispose { track.removeSink(renderer) }
    }

    AndroidView(factory = { renderer }, modifier = modifier)
}
