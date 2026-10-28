plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.headsetbattery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.headsetbattery"
        minSdk = 33
        targetSdk = 35
        versionCode = 2
        versionName = "1.1-az60"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.2.1")
}
