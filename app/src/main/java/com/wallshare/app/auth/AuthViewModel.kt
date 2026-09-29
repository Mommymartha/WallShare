package com.wallshare.app.auth

import android.content.Context
import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AuthViewModel"

class AuthViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _usernameError = MutableStateFlow<String?>(null)
    val usernameError: StateFlow<String?> = _usernameError.asStateFlow()

    private val _signInError = MutableStateFlow<String?>(null)
    val signInError: StateFlow<String?> = _signInError.asStateFlow()

    init {
        // The single source of truth for "am I signed in" is Supabase's own
        // session status stream, not a boolean we maintain ourselves. Every
        // transition (including silent token refresh) flows through here.
        viewModelScope.launch {
            authRepository.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> refreshProfileState()
                    is SessionStatus.NotAuthenticated -> _state.value = AuthState.SignedOut
                    is SessionStatus.Initializing -> _state.value = AuthState.Loading
                    is SessionStatus.RefreshFailure -> _state.value = AuthState.SignedOut
                }
            }
        }
    }

    /**
     * Re-reads the profile row and decides between NeedsUsername and
     * SignedIn. Called right after a successful sign-in, right after a
     * successful claim_username() call, and on manual retry from
     * ProfileSyncError.
     *
     * A null profile here means the session is valid but the row couldn't be
     * found/read - an unexpected sync problem, not a sign-out condition (the
     * handle_new_user() trigger from Step 2 should always have created one).
     * We deliberately do NOT fall back to SignedOut, since that would throw
     * away a perfectly valid session over what's likely a transient issue.
     */
    private suspend fun refreshProfileState() {
        val uid = authRepository.currentUserId()
        if (uid == null) {
            // Session genuinely gone between the event firing and now.
            _state.value = AuthState.SignedOut
            return
        }

        val profile = authRepository.fetchOwnProfile()
        _state.value = when {
            profile == null -> {
                Log.e(TAG, "Authenticated user $uid has no readable profiles row")
                AuthState.ProfileSyncError(uid)
            }
            profile.username.isNullOrBlank() -> AuthState.NeedsUsername(profile.id)
            else -> AuthState.SignedIn(profile)
        }
    }

    /** Lets the UI retry a ProfileSyncError without forcing a fresh sign-in. */
    fun onRetryProfileSync() {
        viewModelScope.launch { refreshProfileState() }
    }

    fun onSignInWithGoogleClicked(context: Context) {
        viewModelScope.launch {
            _signInError.value = null
            _state.value = AuthState.Loading
            try {
                authRepository.signInWithGoogle(context)
                // sessionStatus collector above picks up the new session and
                // drives the next state transition - nothing else to do here.
            } catch (e: GetCredentialCancellationException) {
                // User closed the Google sign-in sheet themselves - not an
                // error worth alarming them about, just log for our own
                // visibility and quietly go back to the login screen.
                Log.d(TAG, "Google sign-in cancelled by user", e)
                _signInError.value = null
                _state.value = AuthState.SignedOut
            } catch (e: Exception) {
                // Everything else (bad OAuth client config, network failure,
                // Supabase rejecting the token, etc). Log the real exception
                // for debugging - this is exactly the kind of thing that's
                // painful to diagnose blind during OAuth setup - but never
                // show raw exception text to the user.
                Log.e(TAG, "Google sign-in failed", e)
                _signInError.value = "Couldn't sign in. Please try again."
                _state.value = AuthState.SignedOut
            }
        }
    }

    fun onSignInWithUsernameClicked(username: String, pass: String) {
        viewModelScope.launch {
            if (username.isBlank() || pass.isBlank()) {
                _signInError.value = "Username and password cannot be empty."
                return@launch
            }
            _signInError.value = null
            _state.value = AuthState.Loading
            try {
                authRepository.signInWithUsername(username, pass)
                // sessionStatus collector picks up the new session
            } catch (e: Exception) {
                Log.e(TAG, "Username sign-in failed", e)
                _signInError.value = "Invalid username or password."
                _state.value = AuthState.SignedOut
            }
        }
    }

    fun onSignUpWithUsernameClicked(username: String, pass: String) {
        viewModelScope.launch {
            if (username.isBlank() || pass.isBlank()) {
                _signInError.value = "Username and password cannot be empty."
                return@launch
            }
            _signInError.value = null
            _state.value = AuthState.Loading
            try {
                authRepository.signUpWithUsername(username, pass)
                // Auto-claim the username so they bypass the NeedsUsername screen
                val result = authRepository.claimUsername(username)
                if (result.isFailure) {
                    Log.w(TAG, "Username auto-claim failed during signup", result.exceptionOrNull())
                }
                refreshProfileState()
            } catch (e: Exception) {
                Log.e(TAG, "Username sign-up failed", e)
                _signInError.value = "Could not create account. Username might be taken."
                _state.value = AuthState.SignedOut
            }
        }
    }

    fun onClaimUsername(username: String) {
        viewModelScope.launch {
            _usernameError.value = null
            authRepository.claimUsername(username)
                .onSuccess { refreshProfileState() }
                .onFailure {
                    Log.w(TAG, "claim_username failed", it)
                    _usernameError.value = it.message
                }
        }
    }

    fun onSignOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    companion object {
        fun factory(authRepository: AuthRepository) = viewModelFactory {
            initializer { AuthViewModel(authRepository) }
        }
    }
}
