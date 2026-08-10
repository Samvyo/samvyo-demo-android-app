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
        // com.samvyo:mediasoup-client is published here (private repo), not Maven
        // Central. Needs a GitHub PAT with read:packages scope, set as GPR_USER/
        // GPR_TOKEN in ~/.gradle/gradle.properties (never committed) or as
        // GITHUB_ACTOR/GITHUB_TOKEN env vars.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Samvyo/mediasoup-android-client-latest")
            credentials {
                username = providers.gradleProperty("GPR_USER").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("GPR_TOKEN").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
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
