package com.wallshare.app.wallpaper.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "WallpaperLockReviver"

/**
 * Watchdog / Reviver Receiver: Ensures WallpaperLockService is immediately restarted
 * if it was terminated, swiped, or if the user unlocks their device while lock is active.
 */
class WallpaperLockReviverReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REVIVE_LOCK = "com.wallshare.app.REVIVE_WALLPAPER_LOCK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Reviver received broadcast action: $action")

        try {
            val vault = WallpaperLockVault(context)
            if (vault.isLockActive()) {
                Log.i(TAG, "Lock is active — ensuring WallpaperLockService is running")
                WallpaperLockService.start(context)
            } else {
                Log.d(TAG, "Lock is not active, ignoring reviver trigger")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in WallpaperLockReviverReceiver", e)
        }
    }
}
