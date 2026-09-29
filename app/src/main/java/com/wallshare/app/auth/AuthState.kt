package com.wallshare.app.auth

import com.wallshare.app.data.model.Profile

/**
 * The only shape of authentication state the UI layer ever sees. Nothing in
 * ui/ touches SupabaseClient, GoogleIdTokenCredential, or a raw session -
 * that all stays inside AuthRepository/AuthViewModel.
 */
sealed interface AuthState {
    /** No active Supabase session. Show the Google Sign-In button. */
    data object SignedOut : AuthState

    /** Session exists, but `profiles.username` is still null. Show username claim screen. */
    data class NeedsUsername(val userId: String) : AuthState

    /** Session exists and a username has been claimed. Normal app usage. */
    data class SignedIn(val profile: Profile) : AuthState

    /** Transient - checking session / talking to Supabase or Google. */
    data object Loading : AuthState

    /**
     * The user has a valid, authenticated Supabase session, but their
     * `profiles` row could not be found or read. This should not normally
     * happen - the Step 2 `handle_new_user()` trigger creates a profile row
     * the instant an auth.users row is created - so this represents an
     * unexpected sync/consistency problem (e.g. the trigger didn't fire, or
     * a transient read failure), NOT a sign-out condition. The session is
     * still valid; retrying the profile fetch is the right recovery path,
     * not discarding the session.
     */
    data class ProfileSyncError(val userId: String) : AuthState
}
