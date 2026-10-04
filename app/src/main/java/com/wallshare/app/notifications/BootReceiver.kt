package com.wallshare.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wallshare.app.wallpaper.WallpaperForegroundService

private const val TAG = "BootReceiver"

/**
 * Restarts the foreground service after the device reboots.
 *
 * Without this, a reboot kills the foreground service and nothing recreates
 * it until the user manually opens the app. START_STICKY only helps when the
 * OS kills the service for memory — it does NOT survive a full reboot.
 *
 * We also check for the QUICKBOOT_POWERON action used by some OEM fast-boot
 * implementations (HTC, some Xiaomi models).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d(TAG, "Device booted — restarting WallpaperForegroundService")
            WallpaperForegroundService.start(context)
        }
    }
}
