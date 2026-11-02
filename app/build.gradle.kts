plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nebula.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nebula.assistant"
        minSdk = 29          // Android 10+
        targetSdk = 35
        // Cloud builds number themselves so each new APK installs over the last one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "2.0.$build"

        ndk {
            // Galaxy phones (arm64) + x86_64 for the Android Studio emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // The cloud build provides the signing key through GitHub secrets.
    val keystorePath = System.getenv("NEBULA_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("nebula") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("NEBULA_KEY_PASSWORD")
                keyAlias = "nebula"
                keyPassword = System.getenv("NEBULA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystorePath != null) signingConfigs.getByName("nebula")
                            else signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // The speech model files must stay uncompressed so the engine can read them from assets.
    androidResources { noCompress += listOf("onnx", "txt") }

    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    // Offline wake word engine (sherpa-onnx, Apache 2.0), bundled locally.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
