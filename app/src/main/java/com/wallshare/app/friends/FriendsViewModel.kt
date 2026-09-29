package com.wallshare.app.friends

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wallshare.app.data.model.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "FriendsViewModel"

data class FriendsUiState(
    val isLoadingFriends: Boolean = true,
    val friends: List<Friend> = emptyList(),
    val incomingRequests: List<IncomingFriendRequest> = emptyList(),
    val outgoingRequests: List<OutgoingFriendRequest> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<Profile> = emptyList(),
    val isSearching: Boolean = false,
    val actionMessage: String? = null,
    val actionError: String? = null,
)

class FriendsViewModel(
    private val friendsRepository: FriendsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FriendsUiState())
    val uiState: StateFlow<FriendsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** Reloads friends + incoming + outgoing requests. Safe to call after any mutation. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingFriends = true) }
            try {
                val friends = friendsRepository.fetchFriends()
                val incoming = friendsRepository.fetchIncomingRequests()
                val outgoing = friendsRepository.fetchOutgoingRequests()
                _uiState.update {
                    it.copy(
                        isLoadingFriends = false,
                        friends = friends,
                        incomingRequests = incoming,
                        outgoingRequests = outgoing,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh friends/requests", e)
                _uiState.update {
                    it.copy(isLoadingFriends = false, actionError = "Couldn't load friends right now.")
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            val results = try {
                friendsRepository.searchUsersByUsername(query)
            } catch (e: Exception) {
                Log.e(TAG, "Username search failed", e)
                emptyList()
            }
            // Guard against a slow, stale search overwriting a newer query's results.
            if (_uiState.value.searchQuery == query) {
                _uiState.update { it.copy(isSearching = false, searchResults = results) }
            }
        }
    }

    fun onSendFriendRequest(username: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(actionError = null, actionMessage = null) }
            friendsRepository.sendFriendRequest(username)
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            actionMessage = if (result.auto_accepted) {
                                "You're now friends with @$username!"
                            } else {
                                "Friend request sent to @$username."
                            },
                        )
                    }
                    refresh()
                }
                .onFailure { error ->
                    Log.w(TAG, "sendFriendRequest failed", error)
                    _uiState.update { it.copy(actionError = error.message) }
                }
        }
    }

    fun onAcceptRequest(requestId: String) = respond(requestId, accept = true)

    fun onRejectRequest(requestId: String) = respond(requestId, accept = false)

    private fun respond(requestId: String, accept: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(actionError = null) }
            friendsRepository.respondToFriendRequest(requestId, accept)
                .onSuccess { refresh() }
                .onFailure { error ->
                    Log.w(TAG, "respondToFriendRequest failed", error)
                    _uiState.update { it.copy(actionError = error.message) }
                }
        }
    }

    fun dismissMessages() {
        _uiState.update { it.copy(actionError = null, actionMessage = null) }
    }

    companion object {
        fun factory(friendsRepository: FriendsRepository) = viewModelFactory {
            initializer { FriendsViewModel(friendsRepository) }
        }
    }
}