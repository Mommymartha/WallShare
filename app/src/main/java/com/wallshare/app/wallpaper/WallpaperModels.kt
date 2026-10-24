package com.wallshare.app.wallpaper

import kotlinx.serialization.Serializable

/** Mirrors a row in the `wallpaper_requests` table (Step 2 migration). */
@Serializable
data class WallpaperRequestRow(
    val id: String,
    val sender_id: String,
    val receiver_id: String,
    val image_path: String,
    val target: String,
    val status: String,
    val created_at: String,
    val expires_at: String? = null,
)

enum class WallpaperTarget { HOME, LOCK, BOTH }

/** Represents an entry in the user's wallpaper history with sender profile information */
@Serializable
data class WallpaperHistoryItem(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val imagePath: String,
    val target: String,
    val status: String,
    val createdAt: String,
    val senderUsername: String? = null,
    val senderDisplayName: String? = null,
    val localFilePath: String? = null,
)