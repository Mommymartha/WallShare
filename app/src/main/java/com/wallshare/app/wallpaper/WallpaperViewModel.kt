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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "WallpaperViewModel"

data class SendWallpaperUiState(
    val isLoadingFriends: Boolean = true,
    val friends: List<Friend> = emptyList(),
    val selectedFriend: Friend? = null,
    val selectedImageUri: Uri? = null,
    val target: WallpaperTarget = WallpaperTarget.BOTH,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val sentSuccessfully: Boolean = false,
)

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
                _uiState.update { it.copy(isLoadingFriends = false, friends = friends) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load friends for wallpaper picker", e)
                _uiState.update {
                    it.copy(isLoadingFriends = false, errorMessage = "Couldn't load friends.")
                }
            }
        }
    }

    fun onFriendSelected(friend: Friend) {
        _uiState.update { it.copy(selectedFriend = friend) }
    }

    fun onImageSelected(uri: Uri?) {
        _uiState.update { it.copy(selectedImageUri = uri) }
    }

    fun onTargetSelected(target: WallpaperTarget) {
        _uiState.update { it.copy(target = target) }
    }

    fun onSendClicked(context: Context) {
        val current = _uiState.value
        val friend = current.selectedFriend
        val uri = current.selectedImageUri
        if (friend == null || uri == null) {
            _uiState.update { it.copy(errorMessage = "Pick a friend and an image first.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, errorMessage = null) }
            try {
                val (bytes, extension) = readImageBytesAndExtension(context, uri)
                wallpaperRepository.sendWallpaper(
                    receiverId = friend.profile.id,
                    imageBytes = bytes,
                    extension = extension,
                    target = current.target,
                ).onSuccess {
                    _uiState.update { it.copy(isSending = false, sentSuccessfully = true) }
                }.onFailure { error ->
                    Log.w(TAG, "sendWallpaper failed", error)
                    _uiState.update {
                        it.copy(isSending = false, errorMessage = error.message ?: "Couldn't send wallpaper.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read selected image", e)
                _uiState.update {
                    it.copy(isSending = false, errorMessage = "Couldn't read the selected image.")
                }
            }
        }
    }

    /**
     * Reads the picked content:// URI as raw bytes and picks a matching
     * extension for the storage path. Note: this uploads the image as-is -
     * V1 does not compress/downscale, so a very large photo can hit the
     * bucket's 10MB limit (Step 2) and fail with a storage error surfaced
     * through the normal error path above, rather than crashing.
     */
    private fun readImageBytesAndExtension(context: Context, uri: Uri): Pair<ByteArray, String> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not open selected image")

        val extension = when (context.contentResolver.getType(uri)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg" // covers image/jpeg; bucket rejects anything else server-side
        }
        return bytes to extension
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun consumeSentSuccessfully() {
        _uiState.update {
            it.copy(sentSuccessfully = false, selectedImageUri = null, selectedFriend = null)
        }
    }

    companion object {
        fun factory(friendsRepository: FriendsRepository, wallpaperRepository: WallpaperRepository) =
            viewModelFactory {
                initializer { WallpaperViewModel(friendsRepository, wallpaperRepository) }
            }
    }
}