plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hr.leo.corsalight"
    compileSdk = 34

    defaultConfig {
        // Beta je posebna aplikacija ("Interior Light Beta"), instalira se pored stabilne 2.x
        applicationId = "hr.leo.corsalight.beta"
        minSdk = 21
        targetSdk = 33
        versionCode = 13
        versionName = "3.0-beta3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // USB serial: CH340, FTDI, CP210x, CDC (Arduino Nano i klonovi)
    implementation("com.github.mik3y:usb-serial-for-android:3.8.0")
}
