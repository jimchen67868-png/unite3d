plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.minieditor"
    compileSdk = 34
    defaultConfig {
        // the editor keeps these defaults; the "Build Game APK" workflow overrides them (-PgameId=... -PappName=...)
        applicationId = (project.findProperty("gameId") as String?) ?: "com.example.minieditor"
        manifestPlaceholders["appLabel"] = (project.findProperty("appName") as String?) ?: "Mini 3D Editor"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.luaj:luaj-jse:3.0.1")
}
