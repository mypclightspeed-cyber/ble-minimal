plugins {
    id 'com.android.application'
}

android {
    namespace 'com.example.blescan'
    compileSdk 34
    
    defaultConfig {
        applicationId "com.example.blescan"
        minSdk 21
        targetSdk 34
        versionCode 1
        versionName "1.0"
        
        // Remove this if you don't need it
        // missingDimensionStrategy 'ads', 'admob'
    }
    
    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.10.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    
    // BLE dependencies
    implementation 'no.nordicsemi.android:ble:2.7.1'
    
    // Optional: Remove if you don't use Google Play Services
    // implementation 'com.google.android.gms:play-services-location:21.0.1'
}
