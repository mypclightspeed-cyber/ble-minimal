plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.blescan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.blescan"
        minSdk = 21
        targetSdk = 34
        versionCode = 2  // Changed from 1 to 2
        versionName = "1.1"  // Changed from 1.0 to 1.1
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true  // Changed from false to true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") { }
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
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
}