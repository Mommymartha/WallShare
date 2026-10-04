package com.wallshare.app.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wallshare.app.WallShareApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.wallshare.app.wallpaper.WallpaperWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OutOfQuotaPolicy

private const val TAG = "WallShareMessaging"

/**
 * Receives FCM data messages, including when the app UI is closed or the process was
 * previously killed by the system (Scenarios 4 & 5) - but only when Android's current
 * background-delivery rules permit it. Delivery is NOT guaranteed in every condition:
 * a user force-stopping the app, or some OEMs' aggressive battery-management skins,
 * can delay or entirely drop a push until the app is next opened by the user. This is
 * an OS-level restriction, not something this architecture can override.
 *
 * Because of that, Supabase (not the push channel) is the source of truth: a
 * startup/foreground reconciliation check that fetches any still-PENDING
 * wallpaper_request for the current user is required to recover from a missed or
 * delayed push. That reconciliation is planned for a later step.
 *
 * A Service isn't lifecycle-scoped the way a ViewModel is, so onNewToken() (which is
 * a plain, non-suspend callback) launches into a manually-owned CoroutineScope that's
 * cancelled in onDestroy(), rather than blocking the callback or leaking a coroutine.
 */
class WallShareMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token received - registering with Supabase")

        val deviceRepository = (application as WallShareApplication).container.deviceRepository
        serviceScope.launch {
            deviceRepository.registerDevice(token)
                .onFailure { e ->
                    // Most commonly: no signed-in user yet (token minted before
                    // login). Not fatal - registerCurrentFcmToken() covers that
                    // case right after sign-in (see MainActivity).
                    Log.w(TAG, "Failed to register new FCM token", e)
                }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // The payload is a hint only - WallpaperWorker re-fetches and
        // re-validates the actual row from Supabase before doing anything.
        // We don't trust image_path/target/etc. from the push itself, only
        // the request id needed to look up the real state.
        val requestId = message.data["request_id"]
        if (requestId == null) {
            Log.w(TAG, "Received FCM message with no request_id - ignoring")
            return
        }

        val workRequest = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setInputData(workDataOf(WallpaperWorker.KEY_REQUEST_ID to requestId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "wallpaper-$requestId",
            ExistingWorkPolicy.KEEP,
            workRequest,
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}