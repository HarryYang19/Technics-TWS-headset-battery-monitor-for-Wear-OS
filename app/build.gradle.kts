
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.headsetbattery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.headsetbattery"

        // Wear OS 3 / Galaxy Watch4 generation and newer
        minSdk = 30
        targetSdk = 35

        versionCode = 8
        versionName = "1.7-live-tile-cleanup"
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
    // Classic TileService + ProtoLayout 1.0 APIs
    // Preserve compatibility with Wear OS 3.
    implementation("androidx.wear.tiles:tiles:1.2.0")
    implementation("androidx.wear.protolayout:protolayout:1.0.0")

    // Guava: ListenableFuture / SettableFuture
    implementation("com.google.guava:guava:33.4.0-android")

    // Wear OS Complications data source
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.2.1")
}
