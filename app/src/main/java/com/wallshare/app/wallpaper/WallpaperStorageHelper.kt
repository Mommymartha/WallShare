package com.wallshare.app.wallpaper

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

private const val TAG = "WallpaperStorageHelper"

object WallpaperStorageHelper {

    /** Returns the local cached wallpaper file for a given request ID */
    fun getLocalWallpaperFile(context: Context, requestId: String): File {
        val dir = File(context.filesDir, "wallpapers").apply {
            if (!exists()) mkdirs()
        }
        return File(dir, "$requestId.jpg")
    }

    /** Saves image bytes to app-internal local storage for fast loading in History */
    fun saveToLocalCache(context: Context, requestId: String, imageBytes: ByteArray): File? {
        return try {
            val file = getLocalWallpaperFile(context, requestId)
            FileOutputStream(file).use { it.write(imageBytes) }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save wallpaper to local cache", e)
            null
        }
    }

    /**
     * Saves image bytes to the device's public Gallery (Pictures/WallShare)
     * using MediaStore so the user can find and use it in their Photos/Gallery app.
     */
    fun saveWallpaperToGallery(
        context: Context,
        imageBytes: ByteArray,
        fileName: String = "WallShare_${System.currentTimeMillis()}.jpg"
    ): Result<Uri> {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WallShare")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return Result.failure(IllegalStateException("Failed to create MediaStore entry"))

            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(imageBytes)
            } ?: return Result.failure(IllegalStateException("Failed to open MediaStore output stream"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            Log.d(TAG, "Saved wallpaper to gallery: $uri")
            Result.success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save wallpaper to gallery", e)
            Result.failure(e)
        }
    }

    /** Directly applies a bitmap as wallpaper (Home, Lock, or Both) */
    fun applyWallpaper(context: Context, bitmap: Bitmap, target: WallpaperTarget): Boolean {
        return try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val flags = when (target) {
                WallpaperTarget.HOME -> WallpaperManager.FLAG_SYSTEM
                WallpaperTarget.LOCK -> WallpaperManager.FLAG_LOCK
                WallpaperTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }

            // Pre-stage in lock vault before setBitmap so it becomes the new locked wallpaper
            val tempFile = File(context.cacheDir, "manual_applied.jpg")
            try {
                FileOutputStream(tempFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                com.wallshare.app.wallpaper.lock.WallpaperLockService.prepareForIncomingWallpaper(
                    context,
                    tempFile.absolutePath
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-stage manual wallpaper to lock service", e)
            }

            wallpaperManager.setBitmap(bitmap, null, true, flags)

            // Sync new wallpaper IDs
            com.wallshare.app.wallpaper.lock.WallpaperLockService.notifySelfWallpaperApplied(
                context,
                tempFile.absolutePath
            )

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply wallpaper", e)
            false
        }
    }
}
