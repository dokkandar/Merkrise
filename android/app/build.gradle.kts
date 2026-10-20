plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hsi.merkrise"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hsi.merkrise"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // M1 builds only the emulator ABI; add arm64-v8a for real devices later.
        ndk { abiFilters += "x86_64" }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // uniffi-generated bindings call the native lib through JNA.
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
