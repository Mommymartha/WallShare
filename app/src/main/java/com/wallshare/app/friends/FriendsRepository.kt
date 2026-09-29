package com.wallshare.app.friends

import com.wallshare.app.data.model.Profile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Owns every direct Supabase call for the friends feature.
 *
 * State-changing operations (sending a request, accepting/rejecting one) go
 * through the Step 2 RPCs - send_friend_request() and
 * respond_to_friend_request() - never a raw insert/update. This isn't
 * optional: `friend_requests` and `friendships` have no client-facing write
 * policy at all in the Step 2 migration, so a raw insert would simply be
 * rejected by RLS. The RPCs are also what make the mutual-request
 * auto-accept and the friendship-creation atomic and server-side.
 *
 * Reads (search, listing) use direct postgrest selects, which the Step 2
 * RLS select policies do allow for a row's participants.
 */
class FriendsRepository(private val supabase: SupabaseClient) {

    private fun currentUserId(): String =
        supabase.auth.currentUserOrNull()?.id
            ?: throw IllegalStateException("FriendsRepository used with no active session")

    /**
     * Username search. `username` is a `citext` column (case-insensitive by
     * definition), and `ilike` is used here as well for clarity/robustness
     * regardless. Excludes the caller's own profile from results.
     */
    suspend fun searchUsersByUsername(query: String, limit: Long = 20): List<Profile> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val uid = currentUserId()

        return supabase.postgrest.from("profiles")
            .select {
                filter {
                    ilike("username", "%$trimmed%")
                    neq("id", uid)
                }
                limit(limit)
            }
            .decodeList<Profile>()
    }

    /**
     * Sends a friend request via send_friend_request() (Step 2), which also
     * resolves the mutual-request case atomically server-side (if the target
     * already sent us a pending request, this accepts theirs instead of
     * creating a duplicate). The client never has to detect or special-case
     * that - it just reads `auto_accepted` off the result to phrase the UI
     * message correctly.
     */
    suspend fun sendFriendRequest(receiverUsername: String): Result<SendFriendRequestRpcResult> {
        return try {
            val result = supabase.postgrest.rpc(
                "send_friend_request",
                buildJsonObject { put("p_receiver_username", receiverUsername) },
            ).decodeSingle<SendFriendRequestRpcResult>()
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(mapFriendActionError(e))
        }
    }

    /** Accept or reject an incoming request via respond_to_friend_request() (Step 2). */
    suspend fun respondToFriendRequest(requestId: String, accept: Boolean): Result<Unit> {
        return try {
            supabase.postgrest.rpc(
                "respond_to_friend_request",
                buildJsonObject {
                    put("p_request_id", requestId)
                    put("p_accept", accept)
                },
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(mapFriendActionError(e))
        }
    }

    /**
     * Incoming pending requests (someone else sent them to us), joined with
     * the sender's profile.
     *
     * This is deliberately two queries rather than a single Postgrest embed:
     * `friend_requests` has two separate foreign keys to `profiles`
     * (sender_id and receiver_id), so an embed needs the exact
     * auto-generated constraint name (e.g. `friend_requests_sender_id_fkey`)
     * to disambiguate which one to follow. Depending on that exact name
     * being right is more fragile than a plain follow-up fetch for what
     * will only ever be a handful of rows for a pending-request list.
     */
    suspend fun fetchIncomingRequests(): List<IncomingFriendRequest> {
        val uid = currentUserId()
        val requests = supabase.postgrest.from("friend_requests")
            .select {
                filter {
                    eq("receiver_id", uid)
                    eq("status", "PENDING")
                }
            }
            .decodeList<FriendRequestRow>()

        val sendersById = fetchProfilesByIds(requests.map { it.sender_id }.distinct())
            .associateBy { it.id }

        return requests.mapNotNull { request ->
            sendersById[request.sender_id]?.let { sender ->
                IncomingFriendRequest(requestId = request.id, sender = sender)
            }
        }
    }

    /** Outgoing pending requests (we sent them), joined with the receiver's profile. */
    suspend fun fetchOutgoingRequests(): List<OutgoingFriendRequest> {
        val uid = currentUserId()
        val requests = supabase.postgrest.from("friend_requests")
            .select {
                filter {
                    eq("sender_id", uid)
                    eq("status", "PENDING")
                }
            }
            .decodeList<FriendRequestRow>()

        val receiversById = fetchProfilesByIds(requests.map { it.receiver_id }.distinct())
            .associateBy { it.id }

        return requests.mapNotNull { request ->
            receiversById[request.receiver_id]?.let { receiver ->
                OutgoingFriendRequest(requestId = request.id, receiver = receiver)
            }
        }
    }

    /**
     * Accepted friends. `friendships` stores a canonically-ordered pair
     * (user_id_a < user_id_b, enforced by a DB check constraint - see Step
     * 2), so for each row the "friend" to display is whichever side isn't
     * the current user. The client has to do this comparison itself; the
     * canonical ordering is a storage/uniqueness detail, not something
     * that's meaningful to show in the UI.
     */
    suspend fun fetchFriends(): List<Friend> {
        val uid = currentUserId()
        val friendships = supabase.postgrest.from("friendships")
            .select {
                filter {
                    or {
                        eq("user_id_a", uid)
                        eq("user_id_b", uid)
                    }
                }
            }
            .decodeList<FriendshipRow>()

        fun otherSide(f: FriendshipRow) = if (f.user_id_a == uid) f.user_id_b else f.user_id_a

        val friendProfilesById = fetchProfilesByIds(friendships.map(::otherSide).distinct())
            .associateBy { it.id }

        return friendships.mapNotNull { friendship ->
            friendProfilesById[otherSide(friendship)]?.let { profile ->
                Friend(friendshipId = friendship.id, profile = profile)
            }
        }
    }

    private suspend fun fetchProfilesByIds(ids: List<String>): List<Profile> {
        if (ids.isEmpty()) return emptyList()
        return supabase.postgrest.from("profiles")
            .select {
                filter { isIn("id", ids) }
            }
            .decodeList<Profile>()
    }
}