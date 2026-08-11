plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.watranslate.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.watranslate.app"
        // minSdk 29 (Android 10) is REQUIRED for AudioPlaybackCaptureConfiguration
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-poc"
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Coroutines for async audio processing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // NOTE: Google Cloud Speech-to-Text & Translation SDKs are heavy gRPC libraries
    // not meant for direct on-device use with embedded credentials.
    // We call them via REST + API key instead (see NetworkClient.kt).
    // If you later want the official SDK, add:
    // implementation("com.google.cloud:google-cloud-speech:4.44.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
