plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.headsetbattery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.headsetbattery"
        // Wear OS 3 starts from Android 11 / API 30 (e.g. Galaxy Watch4 generation).
        minSdk = 30
        targetSdk = 35
        versionCode = 9
        versionName = "1.8-ui-refresh"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Classic TileService + ProtoLayout 1.0 APIs: deliberately compatible with Wear OS 3.
    implementation("androidx.wear.tiles:tiles:1.2.0")
    implementation("androidx.wear.protolayout:protolayout:1.0.0")
    // TileService returns ListenableFuture; SettableFuture is used for bounded async reads.
    implementation("com.google.guava:guava:33.4.0-android")
}
