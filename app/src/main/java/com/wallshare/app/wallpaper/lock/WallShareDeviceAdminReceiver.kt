package com.wallshare.app.wallpaper.lock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "WallShareDeviceAdmin"

/**
 * Device Administration receiver for the Forced Wallpaper Lock feature.
 *
 * When activated, it prevents the app from being uninstalled or force-stopped
 * through normal Android settings, adding an extra layer of protection to the
 * wallpaper lock enforcer.
 *
 * This is OPTIONAL — the user explicitly enables it from the settings sheet
 * and can disable it at any time (after PIN verification).
 */
class WallShareDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled — uninstall & force-stop protection active")
        val vault = WallpaperLockVault(context)
        vault.setDeviceAdminEnabled(true)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin disabled — uninstall & force-stop protection removed")
        val vault = WallpaperLockVault(context)
        vault.setDeviceAdminEnabled(false)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling Device Admin will allow WallShare to be uninstalled or force-stopped. " +
                "Your wallpaper lock protection will be weakened."
    }
}

