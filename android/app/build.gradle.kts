plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.orb.tracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.orb.tracker"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // sideloading: sign release builds with the debug key until a real one is set up
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // the default habits are shared with the Mac app
    sourceSets["main"].assets.srcDirs("../../shared")
    testOptions.unitTests.isReturnDefaultValues = true
}

// No AndroidX: the app uses only the Android framework, which keeps it small and lets
// ../typecheck compile it without the Android SDK.
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303") // Android's org.json is a stub in unit tests
}
