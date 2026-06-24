package com.spends.app.data.backup

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Obtains a client-side OAuth access token for the **drive.appdata** scope via Google's
 * AuthorizationClient (PRD §4.12). No Web client id / Credential Manager needed for a client-side
 * token — it is authorized against the Android OAuth client (app package + signing SHA-1) the user
 * registers in Google Cloud. First use shows an account-pick + consent screen via a resolution
 * intent the UI launches.
 */
@Singleton
class DriveAuthManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val authClient = Identity.getAuthorizationClient(context)

    // Use the literal scope string (the GMS Scopes constant for this is DRIVE_APPFOLDER, easy to
    // mis-name) — least privilege: the app's own hidden Drive appData folder only.
    private val driveAppDataScope = Scope("https://www.googleapis.com/auth/drive.appdata")

    sealed interface AuthResult {
        data class Authorized(val accessToken: String) : AuthResult
        data class NeedsConsent(val intentSender: IntentSender) : AuthResult
    }

    suspend fun authorize(): AuthResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveAppDataScope))
            .build()
        val result = authClient.authorize(request).await()
        val pendingIntent = result.pendingIntent
        return if (result.hasResolution() && pendingIntent != null) {
            AuthResult.NeedsConsent(pendingIntent.intentSender)
        } else {
            AuthResult.Authorized(result.accessToken ?: throw DriveException("Drive did not return an access token."))
        }
    }

    fun accessTokenFromConsent(data: Intent): String {
        val result = authClient.getAuthorizationResultFromIntent(data)
        return result.accessToken ?: throw DriveException("Drive access wasn't granted.")
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
