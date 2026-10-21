plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hr.leo.corsalight"
    compileSdk = 34

    defaultConfig {
        applicationId = "hr.leo.corsalight"
        minSdk = 21
        targetSdk = 33
        versionCode = 10
        versionName = "2.9"
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
