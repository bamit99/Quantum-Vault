package com.aiguru.android_file_encryption.auth

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes

/**
 * Simple AuthManager for Google Drive authentication
 */
class AuthManager(private val context: Context) {

    private val googleSignInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("dummy-client-id") // Replace with actual client ID
            .requestEmail()
            .requestScopes(
                Scope(DriveScopes.DRIVE_FILE),
                Scope(DriveScopes.DRIVE_APPDATA)
            )
            .build()

        GoogleSignIn.getClient(context, gso)
    }

    /**
     * Check if user is signed in
     */
    fun isUserSignedIn(): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }

    /**
     * Get sign-in intent for Google Sign-In
     */
    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    /**
     * Handle sign-in result
     */
    fun handleSignInResult(data: Intent?): Boolean {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(Exception::class.java)
            account != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get Google account credential for Drive API access
     */
    fun getGoogleAccountCredential(): GoogleAccountCredential? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return if (account != null) {
            GoogleAccountCredential.usingOAuth2(
                context,
                listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE_APPDATA)
            ).setSelectedAccount(account.account)
        } else {
            null
        }
    }

    /**
     * Sign out
     */
    fun signOut() {
        googleSignInClient.signOut()
    }
}