import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Read secret keys from local.properties (this file is gitignored — never committed).
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

        // Bake the keys into the APK as BuildConfig constants.
        // The keys are read at compile time from local.properties — they never touch git.
        buildConfigField("String", "ACCESS_KEY",        "\"${localProps.getProperty("ACCESS_KEY", "")}\"")
        buildConfigField("String", "SECRET_ACCESS_KEY", "\"${localProps.getProperty("SECRET_ACCESS_KEY", "")}\"")
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
}
