package com.wallshare.app.wallpaper

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wallshare.app.WallShareApplication
import io.github.jan.supabase.auth.auth
import java.util.concurrent.TimeUnit

private const val TAG = "WallpaperPollWorker"

/**
 * Background worker that polls Supabase for any PENDING wallpaper requests
 * addressed to the current user. This is the bulletproof fallback that does
 * NOT rely on FCM push delivery — it reschedules itself after every run,
 * so it keeps polling as long as the app is installed.
 *
 * WorkManager guarantees execution even if the app process has been killed
 * or the device has been rebooted.
 *
 * For each pending request found, this worker enqueues an individual
 * [WallpaperWorker] to handle the download + apply logic.
 */
class WallpaperPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Polling for pending wallpaper requests...")

        val container = (applicationContext as WallShareApplication).container
        val auth = container.supabase.auth

        // Wait for Supabase to finish loading the persisted auth session.
        auth.awaitInitialization()

        // If the session has expired while in the background, try to refresh
        // it using the persisted refresh token. Without this, the Postgrest
        // call fails with a 401 and the poll silently skips.
        val sessionStatus = auth.sessionStatus.value
        when (sessionStatus) {
            is io.github.jan.supabase.auth.status.SessionStatus.NotAuthenticated,
            is io.github.jan.supabase.auth.status.SessionStatus.RefreshFailure -> {
                try {
                    auth.refreshCurrentSession()
                    Log.d(TAG, "Session refresh succeeded")
                } catch (e: Exception) {
                    Log.w(TAG, "Session refresh failed — no user signed in", e)
                    reschedule()
                    return Result.success()
                }
            }
            else -> { /* Authenticated or Initializing — proceed */ }
        }

        val currentUser = auth.currentUserOrNull()
        if (currentUser == null) {
            Log.d(TAG, "No signed-in user — skipping poll")
            reschedule()
            return Result.success()
        }

        val pendingRequests = try {
            container.wallpaperRepository.getPendingRequests()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch pending requests — will retry next cycle", e)
            reschedule()
            return Result.success() // Don't use retry() — we handle our own schedule
        }

        if (pendingRequests.isEmpty()) {
            Log.d(TAG, "No pending requests found")
        } else {
            Log.d(TAG, "Found ${pendingRequests.size} pending request(s) — enqueuing workers")
            val workManager = WorkManager.getInstance(applicationContext)

            for (request in pendingRequests) {
                val workRequest = OneTimeWorkRequestBuilder<WallpaperWorker>()
                    .setInputData(workDataOf(WallpaperWorker.KEY_REQUEST_ID to request.id))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()

                workManager.enqueueUniqueWork(
                    "wallpaper-${request.id}",
                    ExistingWorkPolicy.KEEP,
                    workRequest,
                )
            }
        }

        reschedule()
        return Result.success()
    }

    /**
     * Re-enqueues this worker with a delay. Using a OneTimeWorkRequest with
     * an initial delay lets us bypass Android's 15-minute minimum for
     * PeriodicWorkRequest, giving us a tighter polling loop.
     */
    private fun reschedule() {
        val nextPoll = OneTimeWorkRequestBuilder<WallpaperPollWorker>()
            .setInitialDelay(POLL_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            nextPoll,
        )

        Log.d(TAG, "Next poll scheduled in $POLL_INTERVAL_MINUTES minute(s)")
    }

    companion object {
        const val UNIQUE_WORK_NAME = "wallshare-poll"

        // TODO: Change back to 15 after testing
        const val POLL_INTERVAL_MINUTES = 1L
    }
}
