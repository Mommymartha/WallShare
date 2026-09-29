package com.wallshare.app.friends

import com.wallshare.app.data.model.Profile
import kotlinx.serialization.Serializable

/** Mirrors a row in the `friend_requests` table (Step 2 migration). */
@Serializable
data class FriendRequestRow(
    val id: String,
    val sender_id: String,
    val receiver_id: String,
    val status: String,
    val created_at: String,
    val updated_at: String,
)

/** Mirrors a row in the `friendships` table (Step 2 migration). */
@Serializable
data class FriendshipRow(
    val id: String,
    val user_id_a: String,
    val user_id_b: String,
    val created_at: String,
)

/** Decode target for the send_friend_request() RPC's `table(request_id, auto_accepted)` return. */
@Serializable
data class SendFriendRequestRpcResult(
    val request_id: String,
    val auto_accepted: Boolean,
)

/**
 * The following three are UI-facing models built by FriendsRepository by
 * joining a friend_requests/friendships row with the *other* person's
 * profile client-side (see FriendsRepository for why this is two queries
 * rather than a Postgrest embed).
 */

data class IncomingFriendRequest(
    val requestId: String,
    val sender: Profile,
)

data class OutgoingFriendRequest(
    val requestId: String,
    val receiver: Profile,
)

data class Friend(
    val friendshipId: String,
    val profile: Profile,
)