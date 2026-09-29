package com.wallshare.app.auth

/**
 * Maps the specific `raise exception '...'` messages from claim_username()
 * (Step 2 migration) into typed errors the UI can branch on cleanly, instead
 * of string-matching Postgres error text in a Composable.
 */
sealed class ClaimUsernameError(message: String) : Exception(message) {
    data object UsernameTaken :
        ClaimUsernameError("That username is already taken.")

    data object InvalidFormat :
        ClaimUsernameError("Usernames must be 3-20 characters: lowercase letters, numbers, or underscores.")

    data object AlreadyClaimed :
        ClaimUsernameError("This account already has a username.")

    data class Unknown(val original: Throwable) :
        ClaimUsernameError(original.message ?: "Something went wrong. Please try again.")
}

/**
 * The RPC surfaces its `raise exception` message text via the thrown
 * exception's message (Postgrest passes the Postgres error message through).
 * NOTE: verify this against the actual supabase-kt error shape once this
 * builds against a real network/Supabase project - this sandbox has no
 * network access to confirm the exact exception type/fields at compile time.
 */
internal fun mapClaimUsernameError(e: Throwable): ClaimUsernameError {
    val msg = e.message.orEmpty()
    return when {
        "username_taken" in msg -> ClaimUsernameError.UsernameTaken
        "invalid_username_format" in msg -> ClaimUsernameError.InvalidFormat
        "username_already_set_or_profile_missing" in msg -> ClaimUsernameError.AlreadyClaimed
        else -> ClaimUsernameError.Unknown(e)
    }
}
