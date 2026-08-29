plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scan2cell.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scan2cell.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 122
        versionName = "1.2.2-local"
    }

    signingConfigs {
        create("scan2cellTest") {
            storeFile = file("scan2cell-personal.jks")
            storePassword = "scan2cell-local-2026"
            keyAlias = "scan2cell"
            keyPassword = "scan2cell-local-2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("scan2cellTest")
            applicationIdSuffix = ""
            versionNameSuffix = ""
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("scan2cellTest")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val cameraXVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
