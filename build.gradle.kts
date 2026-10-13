// Top-level build file. Plugin versions live in gradle/libs.versions.toml.
// AGP 9.x has built-in Kotlin support, so the separate Kotlin Android plugin is not
// declared here — applying it fails the build (see app/build.gradle.kts).
plugins {
    alias(libs.plugins.android.application) apply false
}
