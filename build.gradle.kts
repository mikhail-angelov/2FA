// Top-level build file. Plugin versions live in gradle/libs.versions.toml.
//
// Notes on the toolchain (proven on this machine, AGP 9.1.0):
//  - AGP 9 ships built-in Kotlin, so there is no `kotlin-android` plugin here.
//    The `kotlin.plugin.compose` and `ksp` plugins still apply as usual.
//  - The Gradle wrapper pins 9.6.1, which AGP 9.1.0 requires.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
