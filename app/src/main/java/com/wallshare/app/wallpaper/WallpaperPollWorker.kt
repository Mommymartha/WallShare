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
        // Wrap in a timeout — awaitInitialization() can hang in cold background
        // wakeups if the SDK's internal coroutine scope stalls.
        try {
            kotlinx.coroutines.withTimeout(10_000L) {
                auth.awaitInitialization()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "awaitInitialization() timed out — skipping this poll cycle")
            return Result.success()
        }

        val currentSession = auth.currentSessionOrNull()
        if (currentSession != null) {
            try {
                kotlinx.coroutines.withTimeout(15_000L) {
                    auth.refreshCurrentSession()
                }
                Log.d(TAG, "Session refresh succeeded")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "Session refresh timed out — skipping this poll cycle")
                return Result.success()
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
            Log.d(TAG, "Found ${pendingRequests.size} pending request(s) — executing immediately")
            for (request in pendingRequests) {
                try {
                    WallpaperWorker.executeWallpaperRequest(applicationContext, request.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply polled request ${request.id}", e)
                }
            }
        }

        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "wallshare-poll"
    }
}
