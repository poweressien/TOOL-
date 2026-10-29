plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.toolkit.privacy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.toolkit.privacy"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.tukaani:xz:1.9")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
