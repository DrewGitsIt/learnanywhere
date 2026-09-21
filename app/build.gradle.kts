plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.learnanywhere"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.learnanywhere"
        minSdk = 34            // Android 14
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Dev builds target the attached arm64 phone only; drop this filter
        // (or add x86_64) if you need an emulator image. Halves APK size —
        // the sherpa-onnx AAR ships native libs for 4 ABIs.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OkHttp for the Gemini REST calls (raw, minimal)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Media3 for the Android Auto "media app" bridge (MediaBrowserService)
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Offline PDF text extraction (lets the audiobook read PDFs aloud)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // On-device speech recognition (sherpa-onnx; AAR fetched by
    // scripts/fetch_speech_assets.sh — not in git)
    implementation(fileTree("libs") { include("*.aar") })

    // Room (KSP) for library persistence
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (Android ships it in the platform)
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
}
