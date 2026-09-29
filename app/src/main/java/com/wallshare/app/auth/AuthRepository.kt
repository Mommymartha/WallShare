package com.wallshare.app.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.wallshare.app.BuildConfig
import com.wallshare.app.data.model.Profile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Owns every direct call to Google Credential Manager and to Supabase's
 * Auth/Postgrest APIs for authentication. Nothing in ui/ or auth/AuthViewModel
 * talks to either of those directly - this is the seam the project rule
 * ("UI should never directly contain networking/database implementation
 * details") is enforcing for the auth flow specifically.
 *
 * Session persistence is handled entirely by the Supabase Kotlin SDK's Auth
 * plugin, which stores the session in local storage and restores it on next
 * launch automatically - there's no manual token-saving code here by design.
 */
class AuthRepository(
    private val supabase: SupabaseClient,
    private val credentialManager: CredentialManager,
) {

    /**
     * Live session state. Starts as Initializing while the Auth plugin loads
     * any persisted session from disk, then settles into Authenticated /
     * NotAuthenticated. AuthViewModel observes this rather than polling.
     */
    val sessionStatus: Flow<SessionStatus> = supabase.auth.sessionStatus

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    /**
     * Launches the Credential Manager Google Sign-In bottom sheet, then
     * exchanges the resulting Google ID token for a Supabase session via
     * signInWith(IDToken). Throws on cancellation or failure - the caller
     * (AuthViewModel) decides how to represent that in UI state.
     *
     * Google identity is intentionally never stored as our own concept of
     * identity beyond what Supabase Auth already tracks (auth.users.id) -
     * the WallShare username lives separately in `profiles.username` and is
     * chosen by the user, never derived from their Google account.
     */
    suspend fun signInWithGoogle(context: Context) {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        val result = credentialManager.getCredential(context, request)
        val credential = result.credential

        // Matches Android's own documented Credential Manager pattern: verify this is
        // actually a Google ID token credential before trying to parse it as one,
        // rather than casting straight from the base Credential type.
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw IllegalStateException("Unexpected credential type from Credential Manager")
        }

        val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)

        supabase.auth.signInWith(IDToken) {
            idToken = googleCredential.idToken
            provider = Google
        }
    }

    /**
     * Fetches the caller's own `profiles` row. Returns null only if there is
     * no active session - a signed-in user always has a row by this point,
     * since the Step 2 `handle_new_user()` trigger creates one immediately
     * on signup.
     */
    suspend fun fetchOwnProfile(): Profile? {
        val uid = currentUserId() ?: return null
        return supabase.postgrest.from("profiles")
            .select {
                filter { eq("id", uid) }
            }
            .decodeSingleOrNull<Profile>()
    }

    /**
     * Claims a username via the claim_username() RPC from the Step 2
     * migration. This never writes to `profiles` directly - the database is
     * the sole authority on format validity and uniqueness.
     */
    suspend fun claimUsername(username: String): Result<Unit> {
        return try {
            supabase.postgrest.rpc(
                "claim_username",
                buildJsonObject { put("p_username", username) },
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(mapClaimUsernameError(e))
        }
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }

    suspend fun signInWithUsername(username: String, password: String) {
        val pseudoEmail = "${username.trim().lowercase()}@wallshare.local"
        supabase.auth.signInWith(Email) {
            this.email = pseudoEmail
            this.password = password
        }
    }

    suspend fun signUpWithUsername(username: String, password: String) {
        val pseudoEmail = "${username.trim().lowercase()}@wallshare.local"
        supabase.auth.signUpWith(Email) {
            this.email = pseudoEmail
            this.password = password
        }
    }
}
