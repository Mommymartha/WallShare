package com.wallshare.app.wallpaper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.wallshare.app.R
import com.wallshare.app.WallShareApplication
import io.github.jan.supabase.auth.auth

private const val TAG = "WallpaperWorker"
private const val WORKER_CHANNEL_ID = "wallshare_worker"
private const val WORKER_NOTIFICATION_ID = 10
private const val RESULT_CHANNEL_ID = "wallshare_result"
private const val RESULT_NOTIFICATION_ID = 11

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

    /**
     * Required for setExpedited() on Android < 12 (API 31). On those
     * versions WorkManager promotes the worker to a short-lived foreground
     * service so it can run immediately. On Android 12+ the OS handles
     * expedited jobs natively and this method is not called, but it must
     * still be implemented or setExpedited() throws at enqueue time.
     *
     * On API 29+ (Android 10), ForegroundInfo MUST include a foreground
     * service type matching what's declared in the manifest, or Android 14+
     * throws MissingForegroundServiceTypeException. We use DATA_SYNC since
     * this worker downloads an image and applies it.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        createWorkerChannel()
        val notification = NotificationCompat.Builder(applicationContext, WORKER_CHANNEL_ID)
            .setContentTitle("WallShare")
            .setContentText("Applying wallpaper…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(
                WORKER_NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(WORKER_NOTIFICATION_ID, notification)
        }
    }

    private fun createWorkerChannel() {
        val channel = NotificationChannel(
            WORKER_CHANNEL_ID,
            "WallShare Background Tasks",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows when WallShare is applying a wallpaper"
            setShowBadge(false)
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * Creates the notification channel used for the "wallpaper applied"
     * result notification. Separate from the worker channel so users can
     * control visibility independently.
     */
    private fun createResultChannel() {
        val channel = NotificationChannel(
            RESULT_CHANNEL_ID,
            "Wallpaper Applied",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifies when a new wallpaper has been applied"
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override suspend fun doWork(): Result {
        // Promote to foreground immediately. This satisfies Play Services'
        // requirement that a high-priority FCM data message must trigger a
        // user-visible foreground service or notification within seconds.
        // Without this, Play Services silently deprioritizes future high-
        // priority pushes to normal priority (batched, won't wake the app).
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            // Non-fatal: on Android 12+ expedited jobs run natively without
            // needing a foreground service, so this can fail there. Log it
            // but continue - the actual work can still proceed.
            Log.w(TAG, "Could not promote to foreground", e)
        }

        val requestId = inputData.getString(KEY_REQUEST_ID)
        if (requestId == null) {
            Log.e(TAG, "No request id in input data - cannot proceed")
            return Result.failure()
        }

        val container = (applicationContext as WallShareApplication).container
        val wallpaperRepository = container.wallpaperRepository
        val auth = container.supabase.auth

        // ── Robust auth initialization for cold wakeups ──
        // awaitInitialization() can hang indefinitely in a cold background
        // wakeup if the SDK's internal session-loading coroutine stalls
        // (no UI lifecycle to drive it). Wrap in a timeout so the worker
        // retries instead of hanging silently.
        try {
            kotlinx.coroutines.withTimeout(10_000L) {
                auth.awaitInitialization()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "awaitInitialization() timed out for request $requestId - retrying")
            return Result.retry()
        }

        // After init, check if a session actually exists on disk. If it does
        // but the JWT has expired (which it will after the app has been killed
        // for a while), explicitly refresh it.
        val currentSession = auth.currentSessionOrNull()
        if (currentSession != null) {
            try {
                kotlinx.coroutines.withTimeout(15_000L) {
                    auth.refreshCurrentSession()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "refreshCurrentSession() timed out for request $requestId - retrying")
                return Result.retry()
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh failed for request $requestId", e)
                return Result.retry()
            }
        } else {
            Log.e(TAG, "No persisted session found on disk for request $requestId")
            return Result.failure()
        }

        val currentUserId = auth.currentUserOrNull()?.id
        if (currentUserId == null) {
            Log.w(TAG, "No signed-in user after refresh - retrying wallpaper request $requestId")
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

        // Look up who sent this wallpaper so we can display their username in notifications
        val senderProfile = try {
            wallpaperRepository.fetchSenderProfile(request.sender_id)
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch sender profile for ${request.sender_id}", e)
            null
        }

        val senderHandle = senderProfile?.username?.let { "@$it" }
        val senderDisplayName = senderProfile?.display_name?.takeIf { it.isNotBlank() }
        val senderLabel = when {
            senderDisplayName != null && senderHandle != null -> "$senderDisplayName ($senderHandle)"
            senderHandle != null -> senderHandle
            senderDisplayName != null -> senderDisplayName
            else -> "A friend"
        }

        // 1. Check user preferences for auto-apply on Home and Lock screens
        val settings = container.wallpaperPreferences.getSettings()
        val allowHome = settings.autoApplyHome
        val allowLock = settings.autoApplyLock

        // Determine which screen(s) are allowed to be updated
        val which: Int? = when (request.target) {
            "HOME" -> if (allowHome) WallpaperManager.FLAG_SYSTEM else null
            "LOCK" -> if (allowLock) WallpaperManager.FLAG_LOCK else null
            "BOTH" -> when {
                allowHome && allowLock -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                allowHome -> WallpaperManager.FLAG_SYSTEM
                allowLock -> WallpaperManager.FLAG_LOCK
                else -> null
            }
            else -> null
        }

        if (which == null) {
            Log.d(TAG, "Wallpaper update for request $requestId skipped: blocked by user settings (target=${request.target}, home=$allowHome, lock=$allowLock)")
            // Mark request COMPLETED so it isn't repeatedly retried
            wallpaperRepository.updateStatus(requestId, "COMPLETED")
            // Prune old wallpapers beyond the history limit
            try {
                wallpaperRepository.pruneOldWallpapers(currentUserId, keepCount = WallpaperRepository.MAX_HISTORY_WALLPAPERS)
            } catch (e: Exception) {
                Log.w(TAG, "Storage prune non-fatal error for $requestId", e)
            }

            try {
                createResultChannel()
                val targetLabel = when (request.target) {
                    "HOME" -> "home screen"
                    "LOCK" -> "lock screen"
                    "BOTH" -> "home & lock screens"
                    else -> "wallpaper"
                }
                val skippedTitle = if (senderHandle != null) {
                    "Wallpaper from $senderHandle (Paused)"
                } else {
                    "Wallpaper received (Paused)"
                }
                val skippedNotification = NotificationCompat.Builder(applicationContext, RESULT_CHANNEL_ID)
                    .setContentTitle(skippedTitle)
                    .setContentText("$senderLabel sent you a wallpaper, but auto-apply for $targetLabel is turned off in your settings.")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setAutoCancel(true)
                    .build()
                applicationContext.getSystemService(NotificationManager::class.java)
                    .notify(requestId.hashCode(), skippedNotification)
            } catch (e: Exception) {
                Log.w(TAG, "Could not post skipped notification", e)
            }
            return Result.success()
        }

        // 2. Claim the request if it's new
        if (request.status == "PENDING") {
            val processingResult = wallpaperRepository.updateStatus(requestId, "PROCESSING")
            if (processingResult.isFailure) {
                Log.w(TAG, "Could not mark request $requestId as PROCESSING - retrying")
                return Result.retry()
            }
        } else if (request.status != "PROCESSING") {
            return Result.success() // Defensive check
        }

        // 3. Download with retry logic
        val imageBytes = try {
            wallpaperRepository.downloadImage(request.image_path)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download image for request $requestId - retrying", e)
            return Result.retry()
        }

        // Cache image locally so it is immediately accessible in Wallpaper History
        WallpaperStorageHelper.saveToLocalCache(applicationContext, requestId, imageBytes)

        // 4. Decode image (Terminal failure if corrupt)
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        if (bitmap == null) {
            Log.e(TAG, "Could not decode downloaded image for request $requestId")
            wallpaperRepository.updateStatus(requestId, "FAILED")
            return Result.failure()
        }

        // 5. Apply Wallpaper
        // Some devices/OEMs silently drop setBitmap() when the screen is off
        // or the process is in the background. Acquiring a partial wake lock
        // for the duration of the call ensures the CPU stays awake long enough
        // for the wallpaper to actually be written to disk by the OS.
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE)
            as android.os.PowerManager
        val wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "WallShare::WallpaperApply"
        )

        try {
            wakeLock.acquire(30_000L) // 30 second timeout safety net

            val wallpaperManager = WallpaperManager.getInstance(applicationContext)

            // Retry up to 3 times with a short delay — some devices need a
            // moment after a cold wake before the wallpaper service is ready.
            var applied = false
            for (attempt in 1..3) {
                try {
                    wallpaperManager.setBitmap(bitmap, null, true, which)
                    applied = true
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "setBitmap attempt $attempt failed for request $requestId", e)
                    if (attempt < 3) kotlinx.coroutines.delay(1000L * attempt)
                }
            }

            if (!applied) {
                Log.e(TAG, "All setBitmap attempts failed for request $requestId")
                wallpaperRepository.updateStatus(requestId, "FAILED")
                return Result.failure()
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }

        // 6. Final Status Update
        val completedResult = wallpaperRepository.updateStatus(requestId, "COMPLETED")
        if (completedResult.isFailure) {
            Log.w(TAG, "Wallpaper applied but failed to mark request as COMPLETED - retrying")
            return Result.retry()
        }

        // 7. Auto-delete wallpapers older than 16 so Supabase Storage doesn't overflow,
        // while preserving the user's latest 16 wallpapers in history!
        try {
            wallpaperRepository.pruneOldWallpapers(currentUserId, keepCount = WallpaperRepository.MAX_HISTORY_WALLPAPERS)
            Log.d(TAG, "Pruned old wallpapers beyond latest ${WallpaperRepository.MAX_HISTORY_WALLPAPERS} for user $currentUserId")
        } catch (e: Exception) {
            Log.w(TAG, "Pruning old wallpapers failed (non-fatal)", e)
        }

        // 8. Post a user-visible notification confirming the wallpaper was applied.
        try {
            createResultChannel()
            val targetLabel = when {
                request.target == "BOTH" && !allowLock -> "home screen (lock screen paused)"
                request.target == "BOTH" && !allowHome -> "lock screen (home screen paused)"
                which == (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) -> "home & lock screens"
                which == WallpaperManager.FLAG_SYSTEM -> "home screen"
                which == WallpaperManager.FLAG_LOCK -> "lock screen"
                else -> "wallpaper"
            }
            val title = if (senderHandle != null) {
                "New wallpaper from $senderHandle! \uD83C\uDF1F"
            } else {
                "New wallpaper applied! \uD83C\uDF1F"
            }
            val contentText = "$senderLabel just updated your $targetLabel."

            val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.apply {
                flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = launchIntent?.let {
                android.app.PendingIntent.getActivity(
                    applicationContext,
                    requestId.hashCode(),
                    it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            }

            val resultNotification = NotificationCompat.Builder(applicationContext, RESULT_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setContentIntent(contentPendingIntent)
                .build()
            applicationContext.getSystemService(NotificationManager::class.java)
                .notify(requestId.hashCode(), resultNotification)
        } catch (e: Exception) {
            // Non-fatal: the wallpaper was applied, this is just a courtesy notification
            Log.w(TAG, "Could not post result notification", e)
        }

        Log.d(TAG, "Wallpaper request $requestId completed successfully")
        return Result.success()
    }

    companion object {
        const val KEY_REQUEST_ID = "request_id"
    }
}