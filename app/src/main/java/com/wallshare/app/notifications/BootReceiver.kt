package com.wallshare.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wallshare.app.wallpaper.WallpaperPollWorker
import java.util.concurrent.TimeUnit

private const val TAG = "BootReceiver"

/**
 * Ensures the periodic poll worker is scheduled after device reboot.
 *
 * WorkManager itself persists scheduled work across reboots, but some OEMs
 * (Xiaomi, Huawei) can wipe WorkManager's internal database during their
 * own "cleanup" passes. This receiver re-enqueues with KEEP policy so it's
 * a no-op if the schedule already exists — belt-and-suspenders.
 *
 * We also check for the QUICKBOOT_POWERON action used by some OEM fast-boot
 * implementations (HTC, some Xiaomi models).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d(TAG, "Device booted — ensuring periodic poll worker is scheduled")

            val periodicPoll = PeriodicWorkRequestBuilder<WallpaperPollWorker>(
                15, TimeUnit.MINUTES,
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WallpaperPollWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicPoll,
            )

            // Reboot Trap: Restart WallpaperLockService if Forced Wallpaper Lock was active
            try {
                val lockVault = com.wallshare.app.wallpaper.lock.WallpaperLockVault(context)
                if (lockVault.isLockActive()) {
                    Log.i(TAG, "Wallpaper lock active on boot — restarting WallpaperLockService")
                    com.wallshare.app.wallpaper.lock.WallpaperLockService.start(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore WallpaperLockService on boot", e)
            }
        }
    }
}
