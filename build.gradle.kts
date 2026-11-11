plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library)     apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.kotlin.compose)      apply false
    // Needed because the SDK module (built from source) applies Dokka; see libs.versions.toml.
    alias(libs.plugins.dokka)               apply false
}
