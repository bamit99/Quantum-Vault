// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// NOTE: google-services plugin removed — Firebase auth is not used; Drive uses Google Sign-In.
// Re-add classpath("com.google.gms:google-services:4.4.1") + apply() + google-services.json if Firebase is ever needed.