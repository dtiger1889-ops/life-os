// Top-level build file. Versions are the proven toolchain (AGP 8.5.2 / Kotlin 1.9.25 / Gradle 8.7).
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    // Room (offline-first editing foundation). Version matches the KSP/Kotlin pairing below.
    id("com.google.devtools.ksp") version "1.9.25-1.0.20" apply false
    // Compose Preview Screenshot Testing (PC verification rig). MUST be alpha03 --
    // alpha15+ targets AGP 8.13/9.0 and silently discovers 0 previews on this project's AGP 8.5.2.
    id("com.android.compose.screenshot") version "0.0.1-alpha03" apply false
}
