package com.wallshare.app.wallpaper

import android.app.Application
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "WallpaperHistoryVM"

data class WallpaperHistoryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val items: List<WallpaperHistoryItem> = emptyList(),
    val downloadingIds: Set<String> = emptySet(),
    val savedIds: Set<String> = emptySet(),
    val applyingIds: Set<String> = emptySet(),
    val previewItem: WallpaperHistoryItem? = null,
    val errorMessage: String? = null,
    val userMessage: String? = null,
)

class WallpaperHistoryViewModel(
    application: Application,
    private val wallpaperRepository: WallpaperRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WallpaperHistoryUiState())
    val uiState: StateFlow<WallpaperHistoryUiState> = _uiState.asStateFlow()

    init {
        loadHistory(isRefresh = false)
    }

    fun refresh() {
        loadHistory(isRefresh = true)
    }

    fun loadHistory(isRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isRefresh) {
                _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            } else {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }

            try {
                val context = getApplication<Application>()
                val initialItems = withContext(Dispatchers.IO) {
                    wallpaperRepository.getWallpaperHistory(
                        context,
                        limit = WallpaperRepository.MAX_HISTORY_WALLPAPERS.toLong()
                    )
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        items = initialItems,
                    )
                }

                // Concurrently download and cache any thumbnails not yet stored locally
                val itemsNeedingCache = initialItems.filter { it.localFilePath == null }
                if (itemsNeedingCache.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        itemsNeedingCache.map { item ->
                            async {
                                val cachedFile = wallpaperRepository.downloadAndCacheWallpaper(
                                    context,
                                    item.id,
                                    item.imagePath
                                )
                                if (cachedFile != null) {
                                    item.id to cachedFile.absolutePath
                                } else {
                                    null
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }.let { resolvedPaths ->
                        val pathMap = resolvedPaths.toMap()
                        _uiState.update { state ->
                            val updatedItems = state.items.map { item ->
                                if (pathMap.containsKey(item.id)) {
                                    item.copy(localFilePath = pathMap[item.id])
                                } else {
                                    item
                                }
                            }
                            state.copy(items = updatedItems)
                        }
                    }
                }

                // Opportunistically prune old wallpapers beyond 16 in background to save Supabase space
                withContext(Dispatchers.IO) {
                    try {
                        val currentUserId = initialItems.firstOrNull()?.receiverId
                        if (currentUserId != null) {
                            wallpaperRepository.pruneOldWallpapers(
                                currentUserId,
                                keepCount = WallpaperRepository.MAX_HISTORY_WALLPAPERS
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Background prune error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load wallpaper history", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = "Could not load wallpaper history.",
                    )
                }
            }
        }
    }

    fun downloadWallpaper(item: WallpaperHistoryItem) {
        if (_uiState.value.downloadingIds.contains(item.id)) return

        viewModelScope.launch {
            _uiState.update { it.copy(downloadingIds = it.downloadingIds + item.id) }
            try {
                val context = getApplication<Application>()
                val bytes = withContext(Dispatchers.IO) {
                    // Try reading from local file first
                    val localPath = item.localFilePath
                    val localFile = localPath?.let { File(it) }
                    if (localFile != null && localFile.exists() && localFile.length() > 0) {
                        localFile.readBytes()
                    } else {
                        val downloaded = wallpaperRepository.downloadImage(item.imagePath)
                        WallpaperStorageHelper.saveToLocalCache(context, item.id, downloaded)
                        downloaded
                    }
                }

                val result = withContext(Dispatchers.IO) {
                    WallpaperStorageHelper.saveWallpaperToGallery(
                        context,
                        bytes,
                        "WallShare_${item.id.take(8)}_${System.currentTimeMillis()}.jpg"
                    )
                }

                if (result.isSuccess) {
                    _uiState.update {
                        it.copy(
                            downloadingIds = it.downloadingIds - item.id,
                            savedIds = it.savedIds + item.id,
                            userMessage = "Wallpaper saved to Photos! \uD83D\uDCF8",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            downloadingIds = it.downloadingIds - item.id,
                            errorMessage = "Failed to save wallpaper to gallery.",
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download wallpaper ${item.id}", e)
                _uiState.update {
                    it.copy(
                        downloadingIds = it.downloadingIds - item.id,
                        errorMessage = "Error downloading wallpaper.",
                    )
                }
            }
        }
    }

    fun applyWallpaper(item: WallpaperHistoryItem, target: WallpaperTarget) {
        if (_uiState.value.applyingIds.contains(item.id)) return

        viewModelScope.launch {
            _uiState.update { it.copy(applyingIds = it.applyingIds + item.id) }
            try {
                val context = getApplication<Application>()
                val bitmap = withContext(Dispatchers.IO) {
                    val localPath = item.localFilePath
                    val localFile = localPath?.let { File(it) }
                    val bytes = if (localFile != null && localFile.exists() && localFile.length() > 0) {
                        localFile.readBytes()
                    } else {
                        wallpaperRepository.downloadImage(item.imagePath)
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }

                if (bitmap != null) {
                    val applied = withContext(Dispatchers.IO) {
                        WallpaperStorageHelper.applyWallpaper(context, bitmap, target)
                    }

                    if (applied) {
                        val screenLabel = when (target) {
                            WallpaperTarget.HOME -> "home screen"
                            WallpaperTarget.LOCK -> "lock screen"
                            WallpaperTarget.BOTH -> "both screens"
                        }
                        _uiState.update {
                            it.copy(
                                applyingIds = it.applyingIds - item.id,
                                userMessage = "Wallpaper applied to $screenLabel! ✨",
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                applyingIds = it.applyingIds - item.id,
                                errorMessage = "Failed to set wallpaper.",
                            )
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            applyingIds = it.applyingIds - item.id,
                            errorMessage = "Could not decode wallpaper image.",
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply wallpaper ${item.id}", e)
                _uiState.update {
                    it.copy(
                        applyingIds = it.applyingIds - item.id,
                        errorMessage = "Error applying wallpaper.",
                    )
                }
            }
        }
    }

    fun onPreviewItem(item: WallpaperHistoryItem?) {
        _uiState.update { it.copy(previewItem = item) }
    }

    fun dismissUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun dismissErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        fun factory(
            application: Application,
            wallpaperRepository: WallpaperRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WallpaperHistoryViewModel(application, wallpaperRepository)
            }
        }
    }
}
