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

private const val TAG = "WallpaperPollWorker"

/**
 * Background worker that polls Supabase for any PENDING wallpaper requests
 * addressed to the current user. This is the bulletproof fallback that does
 * NOT rely on FCM push delivery.
 *
 * Enqueued as a PeriodicWorkRequest (every 15 min) by [WallShareApplication]
 * and [com.wallshare.app.notifications.BootReceiver]. WorkManager guarantees
 * execution even if the app process has been killed or the device rebooted.
 *
 * For each pending request found, this worker enqueues an individual
 * [WallpaperWorker] (as expedited work) to handle the download + apply.
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

        // Robust session refresh for cold wakeups. The old approach of
        // checking sessionStatus.value was racy — during cold starts,
        // supabase-kt can briefly emit LoadingFromStorage or NotAuthenticated
        // before the refresh token exchange completes. Instead, check if a
        // session actually exists on disk and force-refresh the JWT.
        val currentSession = auth.currentSessionOrNull()
        if (currentSession != null) {
            try {
                auth.refreshCurrentSession()
                Log.d(TAG, "Session refresh succeeded")
            } catch (e: Exception) {
                Log.w(TAG, "Session refresh failed — token may be revoked", e)
                return Result.success()
            }
        }

        val currentUser = auth.currentUserOrNull()
        if (currentUser == null) {
            Log.d(TAG, "No signed-in user — skipping poll")
            return Result.success()
        }

        val pendingRequests = try {
            container.wallpaperRepository.getPendingRequests()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch pending requests — will retry next cycle", e)
            return Result.success()
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

        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "wallshare-poll"
    }
}
