package com.wallshare.app.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wallshare.app.WallShareApplication
import io.github.jan.supabase.auth.auth

private const val TAG = "WallpaperWorker"

/**
 * Downloads and applies a wallpaper for the currently signed-in user.
 *
 * The FCM payload that triggers this worker is treated as a hint only - it
 * supplies a request id, nothing more. This worker re-fetches the actual row
 * from Supabase and verifies that it belongs to the current user and is
 * either PENDING or PROCESSING.
 */
class WallpaperWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val requestId = inputData.getString(KEY_REQUEST_ID)
        if (requestId == null) {
            Log.e(TAG, "No request id in input data - cannot proceed")
            return Result.failure()
        }

        val container = (applicationContext as WallShareApplication).container
        val wallpaperRepository = container.wallpaperRepository
        val currentUserId = container.supabase.auth.currentUserOrNull()?.id
        if (currentUserId == null) {
            Log.w(TAG, "No signed-in user - retrying wallpaper request $requestId")
            return Result.retry()
        }

        val request = try {
            wallpaperRepository.fetchActiveRequest(requestId, currentUserId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch wallpaper request $requestId", e)
            return Result.retry()
        }

        if (request == null) {
            Log.d(TAG, "Request $requestId is no longer active for this user - skipping")
            return Result.success()
        }

        // 1. Claim the request if it's new
        if (request.status == "PENDING") {
            val processingResult = wallpaperRepository.updateStatus(requestId, "PROCESSING")
            if (processingResult.isFailure) {
                Log.w(TAG, "Could not mark request $requestId as PROCESSING - retrying")
                return Result.retry()
            }
        } else if (request.status != "PROCESSING") {
            return Result.success() // Defensive check
        }

        // 2. Download with retry logic
        val imageBytes = try {
            wallpaperRepository.downloadImage(request.image_path)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download image for request $requestId - retrying", e)
            return Result.retry()
        }

        // 3. Decode image (Terminal failure if corrupt)
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        if (bitmap == null) {
            Log.e(TAG, "Could not decode downloaded image for request $requestId")
            wallpaperRepository.updateStatus(requestId, "FAILED")
            return Result.failure()
        }

        // 4. Apply Wallpaper (Terminal failure if OS rejects it)
        try {
            val wallpaperManager = WallpaperManager.getInstance(applicationContext)
            val which = when (request.target) {
                "HOME" -> WallpaperManager.FLAG_SYSTEM
                "LOCK" -> WallpaperManager.FLAG_LOCK
                "BOTH" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                else -> {
                    wallpaperRepository.updateStatus(requestId, "FAILED")
                    return Result.failure()
                }
            }
            wallpaperManager.setBitmap(bitmap, null, true, which)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply wallpaper for request $requestId", e)
            wallpaperRepository.updateStatus(requestId, "FAILED")
            return Result.failure()
        }

        // 5. Final Status Update
        val completedResult = wallpaperRepository.updateStatus(requestId, "COMPLETED")
        if (completedResult.isFailure) {
            Log.w(TAG, "Wallpaper applied but failed to mark request as COMPLETED - retrying")
            return Result.retry()
        }

        Log.d(TAG, "Wallpaper request $requestId completed successfully")
        return Result.success()
    }

    companion object {
        const val KEY_REQUEST_ID = "request_id"
    }
}