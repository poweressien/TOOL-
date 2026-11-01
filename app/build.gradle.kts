import org.gradle.api.plugins.BasePluginExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    // The Privacy Toolkit code (Compose) lives in com.toolkit.privacy and the EKPE suite code (views + Python)
    // lives in com.inwekobong.ekpe. Both are compiled into this one module / one APK.
    namespace = "com.toolkit.privacy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.toolkit.privacy"
        minSdk = 26            // EKPE needs 26 (PrivacyToolkit alone was 24)
        targetSdk = 34
        versionCode = 5
        versionName = "4.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Fixed signing key (from EKPE) so every build installs over the previous one.
    signingConfigs {
        create("fixed") {
            storeFile = file("ekpe.jks")
            storePassword = "vint12345"
            keyAlias = "vint"
            keyPassword = "vint12345"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
        getByName("release") { signingConfig = signingConfigs.getByName("fixed"); isMinifyEnabled = false }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/DEPENDENCIES"
        resources.excludes += "/META-INF/INDEX.LIST"
        // several native SDKs (ML Kit, MediaPipe, Chaquopy, WireGuard) ship the same C++ runtime
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

// APK is named EKPE-debug.apk / EKPE-release.apk
extensions.configure<BasePluginExtension> { archivesName.set("EKPE") }

// EKPE: Python engine (Intel / Script Lab) running on-device
chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("flask")
            install("requests")
            install("beautifulsoup4")
            install("phonenumbers")
            install("dnspython")
            install("tldextract")
            install("python-whois")
            install("python-dotenv")
            install("openpyxl")
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")   // one distinct icon per tool tile
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
    // RealityLens: camera, on-device recognition, translation
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // EKPE suite: offline AI (Labs), phone-number intel, Google Drive sign-in (Cloud)
    implementation("com.google.mediapipe:tasks-genai:0.10.14")
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.40")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
