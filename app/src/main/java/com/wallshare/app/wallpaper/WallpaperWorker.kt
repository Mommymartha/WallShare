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
        // When the OS wakes the app from a killed state (FCM or WorkManager),
        // Application.onCreate() initializes SupabaseClientProvider which reads
        // the persisted session from SharedPreferences. awaitInitialization()
        // waits for that disk read to complete.
        auth.awaitInitialization()

        // After init, check if a session actually exists on disk. If it does
        // but the JWT has expired (which it will after the app has been killed
        // for a while), explicitly refresh it. The old approach of checking
        // sessionStatus.value was racy - during cold starts, supabase-kt can
        // briefly emit LoadingFromStorage or NotAuthenticated before the
        // refresh token exchange completes.
        val currentSession = auth.currentSessionOrNull()
        if (currentSession != null) {
            try {
                auth.refreshCurrentSession()
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

        // 6. Post a user-visible notification confirming the wallpaper was applied.
        // This also helps maintain the app's FCM high-priority quota - Play Services
        // expects a user-visible side-effect when a high-priority data message wakes
        // the app. Without this, future pushes get downgraded to normal priority.
        try {
            createResultChannel()
            val targetLabel = when (request.target) {
                "HOME" -> "home screen"
                "LOCK" -> "lock screen"
                "BOTH" -> "home & lock screen"
                else -> "wallpaper"
            }
            val resultNotification = NotificationCompat.Builder(applicationContext, RESULT_CHANNEL_ID)
                .setContentTitle("New wallpaper applied! \uD83C\uDF1F")
                .setContentText("Your $targetLabel wallpaper was just updated")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .build()
            applicationContext.getSystemService(NotificationManager::class.java)
                .notify(RESULT_NOTIFICATION_ID, resultNotification)
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