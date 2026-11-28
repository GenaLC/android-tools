plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.callagent.gateway"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.callagent.gateway"
        // Android 7 is the floor: the app degrades on old versions (no
        // privileged telephony APIs, no channel-based notifications) but the
        // SIP, RTP and GSM legs are version-independent. See the guards in
        // service/Compat.kt and service/GatewayService.kt.
        minSdk = 24
        targetSdk = 35
        versionCode = 464
        versionName = "1.4.25"
    }

    // A release build is signed with the same debug key the debug build uses.
    // That is deliberate: it keeps the signature identical, so a release APK can
    // replace a debug one inside the Magisk module without PackageManager
    // rejecting it for a signature mismatch.  The point of building release here
    // is not secrecy, it is `debuggable=false` — ART compiles a debuggable app in
    // a deoptimizable mode with much weaker inlining, which costs real CPU in the
    // per-frame audio loops.
    signingConfigs {
        create("shared") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // The SIP dialog and the early-media logic are plain Kotlin, but they log
    // through android.util.Log, which the stub jar refuses to run.  Returning
    // the defaults instead lets the dialog state machine be tested on its own
    // — without it, every test that touches a real code path dies on a log
    // line.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")
}
