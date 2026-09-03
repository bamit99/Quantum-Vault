package com.aiguru.android_file_encryption

import android.app.Application
import timber.log.Timber

/**
 * Application class. Plants Timber ONLY in debug builds — release builds get
 * a no-op tree, so any accidental log of sensitive material ships as nothing
 * (audit P2-1: logging discipline).
 */
class QuantumVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // AGP 8 doesn't generate BuildConfig by default — use the app flag instead.
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            Timber.plant(Timber.DebugTree())
        }
    }
}