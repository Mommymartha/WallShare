package com.wallshare.app.wallpaper

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wallshare.app.friends.Friend
import com.wallshare.app.friends.FriendsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "WallpaperViewModel"

data class SendWallpaperUiState(
    val isLoadingFriends: Boolean = true,
    val friends: List<Friend> = emptyList(),
    val selectedFriends: Set<String> = emptySet(), // Set of friendshipId
    val selectedImageUri: Uri? = null,
    val target: WallpaperTarget = WallpaperTarget.BOTH,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val sentSuccessfully: Boolean = false,
) {
    /** Compatibility helper for single-friend reference */
    val selectedFriend: Friend?
        get() = friends.firstOrNull { it.friendshipId in selectedFriends }
}

class WallpaperViewModel(
    private val friendsRepository: FriendsRepository,
    private val wallpaperRepository: WallpaperRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SendWallpaperUiState())
    val uiState: StateFlow<SendWallpaperUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val friends = friendsRepository.fetchFriends()
                _uiState.update {
                    it.copy(
                        isLoadingFriends = false,
                        friends = friends,
                        // Auto-select when there is only one friend
                        selectedFriends = if (friends.size == 1) setOf(friends.first().friendshipId) else emptySet()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load friends for wallpaper picker", e)
                _uiState.update {
                    it.copy(isLoadingFriends = false, errorMessage = "Couldn't load friends.")
                }
            }
        }
    }

    /** Toggles a friend's selection state */
    fun onToggleFriend(friend: Friend) {
        _uiState.update { current ->
            val newSelection = if (friend.friendshipId in current.selectedFriends) {
                current.selectedFriends - friend.friendshipId
            } else {
                current.selectedFriends + friend.friendshipId
            }
            current.copy(selectedFriends = newSelection)
        }
    }

    /** Selects or deselects all friends at once */
    fun onSelectAllFriends() {
        _uiState.update { current ->
            val allIds = current.friends.map { it.friendshipId }.toSet()
            val newSelection = if (current.selectedFriends.size == allIds.size) {
                emptySet()
            } else {
                allIds
            }
            current.copy(selectedFriends = newSelection)
        }
    }

    fun onFriendSelected(friend: Friend) = onToggleFriend(friend)

    fun onImageSelected(uri: Uri?) {
        _uiState.update { it.copy(selectedImageUri = uri) }
    }

    fun onTargetSelected(target: WallpaperTarget) {
        _uiState.update { it.copy(target = target) }
    }

    fun onSendClicked(context: Context) {
        val current = _uiState.value
        val selectedFriendsList = current.friends.filter { it.friendshipId in current.selectedFriends }
        val uri = current.selectedImageUri
        if (selectedFriendsList.isEmpty() || uri == null) {
            _uiState.update { it.copy(errorMessage = "Pick at least one friend and an image first.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, errorMessage = null) }
            try {
                val (bytes, extension) = readImageBytesAndExtension(context, uri)

                // Dispatch wallpaper upload and request creation to all selected friends concurrently
                val results = coroutineScope {
                    selectedFriendsList.map { friend ->
                        async {
                            friend to wallpaperRepository.sendWallpaper(
                                receiverId = friend.profile.id,
                                imageBytes = bytes,
                                extension = extension,
                                target = current.target,
                            )
                        }
                    }.awaitAll()
                }

                val failures = results.filter { it.second.isFailure }
                val successes = results.filter { it.second.isSuccess }

                if (failures.isEmpty()) {
                    _uiState.update { it.copy(isSending = false, sentSuccessfully = true) }
                } else if (successes.isNotEmpty()) {
                    val failedNames = failures.joinToString(", ") { "@${it.first.profile.username ?: "friend"}" }
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            sentSuccessfully = true,
                            errorMessage = "Sent to ${successes.size} friend(s), but failed for: $failedNames",
                        )
                    }
                } else {
                    val firstError = failures.first().second.exceptionOrNull()?.message
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            errorMessage = firstError ?: "Failed to send wallpaper to selected friends.",
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read or send image", e)
                _uiState.update {
                    it.copy(isSending = false, errorMessage = "Couldn't read the selected image.")
                }
            }
        }
    }

    private fun readImageBytesAndExtension(context: Context, uri: Uri): Pair<ByteArray, String> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not open selected image")

        val extension = when (context.contentResolver.getType(uri)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        return bytes to extension
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun consumeSentSuccessfully() {
        _uiState.update {
            it.copy(sentSuccessfully = false, selectedImageUri = null, selectedFriends = emptySet())
        }
    }

    companion object {
        fun factory(friendsRepository: FriendsRepository, wallpaperRepository: WallpaperRepository) =
            viewModelFactory {
                initializer { WallpaperViewModel(friendsRepository, wallpaperRepository) }
            }
    }
}