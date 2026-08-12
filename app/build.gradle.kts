import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Read local dev config from local.properties (this file is gitignored — never committed).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.samvyo.demo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.samvyo.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // ACCESS_KEY/SECRET_ACCESS_KEY are deliberately NOT here — bundling them in the app
        // lets anyone extract them from the APK. Instead the app calls a customer-run token
        // server (react-native-demo-app-server pattern) that holds the keys server-side and
        // hands back a session token. Only that server's URL is baked in.
        buildConfigField("String", "TOKEN_SERVER_URL", "\"${localProps.getProperty("TOKEN_SERVER_URL", "http://10.0.2.2:5100")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            // mediasoup-client bundles native .so files for each ABI.
            // AGP 9 fails on duplicates — pick the first copy encountered.
            pickFirsts += setOf("**/*.so")
        }
    }
}

dependencies {
    // Local development: use the SDK module compiled from source.
    // When switching to JitPack, replace this with:
    //   implementation("com.github.Samvyo:samvyo-android-sdk:TAG")
    implementation(project(":samvyo-sdk"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp) // for fetchSessionTokenFromServer() — see RoomViewModel.kt
}
