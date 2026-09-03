plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aiguru.android_file_encryption"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aiguru.android_file_encryption"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Keystore path/password resolve from gradle.properties (keystore.* keys).
            // NEVER hardcode them here — gradle.properties is user-local and gitignored.
            val ksFile = project.findProperty("keystore.file") as? String
            val ksPass = project.findProperty("keystore.password") as? String
            val ksAlias = project.findProperty("keystore.alias") as? String
            if (ksFile != null && ksPass != null && ksAlias != null) {
                storeFile = file(ksFile)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    
    // Security & Encryption
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric) // dormant legacy path (BiometricManager) — keep until v5 decision
    
    // Cloud Storage
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.drive)
    
    // Google Play Services Auth
    implementation("com.google.android.gms:play-services-auth:21.0.0")

    // Firebase Authentication (commented out until google-services.json is configured)
    // implementation(platform(libs.firebase.bom))
    // implementation(libs.firebase.auth)
    
    // AWS SDK
    implementation("com.amazonaws:aws-android-sdk-s3:2.77.0")
    implementation("com.amazonaws:aws-android-sdk-core:2.77.0")
    
    // Azure Storage
    implementation("com.microsoft.azure.android:azure-storage-android:2.0.0@aar")
    
    // Jetpack Navigation
    implementation("androidx.navigation:navigation-compose:2.8.4")
    
    // Material Icons
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    
    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    
    // Post-quantum crypto (ML-KEM-768 / X25519) — Bouncy Castle
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    
    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    
    // Logging
    implementation(libs.timber)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}