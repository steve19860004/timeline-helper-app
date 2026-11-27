plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "tw.local.timeline"
    compileSdk = 35
    defaultConfig {
        applicationId = "tw.local.timeline"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        create("release") {
            val kf = System.getenv("SIGNING_KEYSTORE_FILE")
            if (kf != null && file(kf).exists()) {
                storeFile = file(kf)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("SIGNING_KEYSTORE_FILE") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
