plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.bmclink"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.bmclink"
        minSdk = 21          // auch alte Micro-USB-Handys (Android 5+)
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            // Kein Minify: DhcpMain wird per app_process über den Klassennamen gestartet.
            isMinifyEnabled = false
            // Mit Debug-Schlüssel signieren, damit die Release-APK direkt installierbar ist.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
