package com.wallshare.app.friends

/**
 * Maps the specific `raise exception '...'` messages from send_friend_request()
 * and respond_to_friend_request() (Step 2 migration) into typed errors the UI
 * can show cleanly, instead of raw Postgres error text.
 */
sealed class FriendActionError(message: String) : Exception(message) {
    data object UserNotFound :
        FriendActionError("No user found with that username.")

    data object CannotFriendSelf :
        FriendActionError("You can't send a friend request to yourself.")

    data object AlreadyFriends :
        FriendActionError("You're already friends with this user.")

    data object RequestAlreadyPending :
        FriendActionError("A request between you two is already pending.")

    data object NotAuthorized :
        FriendActionError("You're not allowed to respond to this request.")

    data object RequestNotPending :
        FriendActionError("This request has already been handled.")

    data object RequestNotFound :
        FriendActionError("That request no longer exists.")

    data class Unknown(val original: Throwable) :
        FriendActionError(original.message ?: "Something went wrong. Please try again.")
}

internal fun mapFriendActionError(e: Throwable): FriendActionError {
    val msg = e.message.orEmpty()
    return when {
        "user_not_found" in msg -> FriendActionError.UserNotFound
        "cannot_friend_self" in msg -> FriendActionError.CannotFriendSelf
        "already_friends" in msg -> FriendActionError.AlreadyFriends
        "request_already_pending" in msg -> FriendActionError.RequestAlreadyPending
        "not_authorized" in msg -> FriendActionError.NotAuthorized
        "request_not_pending" in msg -> FriendActionError.RequestNotPending
        "request_not_found" in msg -> FriendActionError.RequestNotFound
        else -> FriendActionError.Unknown(e)
    }
}