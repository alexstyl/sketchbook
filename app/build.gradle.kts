plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.alexstyl.sketchbook"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.alexstyl.sketchbook"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    packaging {
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4") {
        exclude(group = "com.onyx.android.sdk", module = "onyxsdk-geometry")
    }
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.5")
    implementation("com.onyx.android.sdk:onyxsdk-base:1.8.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
