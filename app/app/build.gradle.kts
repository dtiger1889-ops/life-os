plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    // PC verification rig -- see root build.gradle.kts for the alpha03 version note.
    id("com.android.compose.screenshot")
}

android {
    namespace = "com.example.lifeos"
    compileSdk = 35

    // Compose Preview Screenshot Testing: renders @Preview composables to PNG on the JVM,
    // no emulator. gradlew updateDebugScreenshotTest records goldens (app/src/debug/
    // screenshotTest/reference/); validateDebugScreenshotTest diffs against them.
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        applicationId = "com.example.lifeos"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        // Compose compiler extension matched to Kotlin 1.9.25.
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Offline-editing foundation: Room + outbox + WorkManager sync.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Compose Preview Screenshot Testing. Do NOT add
    // com.android.tools.screenshot:screenshot-validation-api -- it drags in kotlin-stdlib 2.2
    // (too new for the Kotlin 1.9.25 compiler) and breaks the build. Plain @Preview needs only
    // ui-tooling.
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
}
