package com.wallshare.app.notifications

import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "DeviceRepository"

/**
 * Owns FCM token retrieval and its registration into the `devices` table.
 *
 * Registration always goes through the register_device() RPC (Step 2),
 * never a raw insert. `push_token` has a UNIQUE constraint - a raw insert
 * would simply fail if this exact token was previously registered under a
 * different account (reinstall, different user signing in on the same
 * phone). register_device() is a SECURITY DEFINER function that can safely
 * reassign that row to whichever user is currently authenticated; a normal
 * RLS-scoped insert/update could not, since the existing row wouldn't
 * belong to the new caller.
 */
class DeviceRepository(private val supabase: SupabaseClient) {

    /**
     * Fetches the current FCM token and registers it for the signed-in
     * user. Call this once right after reaching AuthState.SignedIn - it
     * covers the case where the token was already generated before the user
     * signed in (onNewToken() only fires when a *new* token is minted, not
     * on every app start, so a token generated pre-login would otherwise
     * never get associated with the account).
     */
    suspend fun registerCurrentFcmToken(): Result<Unit> {
        return try {
            Log.d(TAG, "Fetching current FCM token from Firebase...")
            val token = fetchCurrentFcmToken()
            Log.d(TAG, "FCM token obtained (${token.take(12)}...${token.takeLast(6)}), registering with Supabase...")
            val result = registerDevice(token)
            if (result.isSuccess) {
                Log.d(TAG, "✅ Device registered successfully in Supabase devices table")
            } else {
                Log.e(TAG, "❌ Supabase register_device RPC failed", result.exceptionOrNull())
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch FCM token from Firebase", e)
            Result.failure(e)
        }
    }

    /** Registers a specific token - used directly by onNewToken() when a new one is minted. */
    suspend fun registerDevice(pushToken: String): Result<Unit> {
        return try {
            supabase.postgrest.rpc(
                "register_device",
                buildJsonObject {
                    put("p_platform", "ANDROID")
                    put("p_push_token", pushToken)
                    put("p_device_name", Build.MODEL)
                },
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "register_device RPC threw: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Wraps FirebaseMessaging's Task-based getToken() in a suspend function.
     * Deliberately a manual suspendCancellableCoroutine rather than pulling
     * in kotlinx-coroutines-play-services purely for `.await()` - this is a
     * single call site, so the extra dependency isn't worth it.
     */
    private suspend fun fetchCurrentFcmToken(): String = suspendCancellableCoroutine { continuation ->
        val fcm = FirebaseMessaging.getInstance()
        fcm.token
            .addOnSuccessListener { token -> continuation.resume(token) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Firebase token fetch failed: ${e.javaClass.simpleName}: ${e.message}")
                // If the local cache is jammed/rate-limited, nuke it and try one more time
                if (e.message?.contains("TOO_MANY_REGISTRATIONS") == true) {
                    Thread {
                        try {
                            fcm.deleteToken() // Clears the corrupted cache
                            fcm.token
                                .addOnSuccessListener { t -> continuation.resume(t) }
                                .addOnFailureListener { e2 -> continuation.resumeWithException(e2) }
                        } catch (ex: Exception) {
                            continuation.resumeWithException(ex)
                        }
                    }.start()
                } else {
                    continuation.resumeWithException(e)
                }
            }
    }
}