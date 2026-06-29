pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "samvyo-demo"
include(":app")

// ── LOCAL DEVELOPMENT MODE ────────────────────────────────────────────────────
// The SDK source lives in a separate repo on this machine.
// Including it here means Gradle compiles it from source — no publishing needed.
//
// To switch to JitPack later:
//   1. Delete the two lines below.
//   2. In app/build.gradle.kts change:
//        implementation(project(":samvyo-sdk"))
//      to:
//        implementation("com.github.Samvyo:samvyo-android-sdk:TAG")
//   3. Add  maven { url = uri("https://jitpack.io") }  to repositories above.
include(":samvyo-sdk")
project(":samvyo-sdk").projectDir = File("/home/saurav/Android-app/samvyo-android-sdk/samvyo-sdk")
