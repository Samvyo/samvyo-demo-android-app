package io.samvyo.demo

import android.app.Application
import org.mediasoup.droid.MediasoupClient

// Application is the global singleton that Android creates before any Activity.
// We use it to initialize the mediasoup native library once for the entire app lifetime.
// android:name=".SamvyoApp" in AndroidManifest.xml tells Android to use this class.
class SamvyoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Loads the mediasoup + WebRTC native (.so) libraries from the AAR.
        // Must happen before Device, PeerConnectionFactory, or any WebRTC class is used.
        MediasoupClient.initialize(applicationContext)
    }
}
