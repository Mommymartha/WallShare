package com.wallshare.app.wallpaper.lock

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "DeviceAdminHelper"

/**
 * Helper for managing Device Administration privileges for WallShare.
 *
 * When Device Admin is active:
 * - The app cannot be uninstalled via Settings without first deactivating admin
 * - The app cannot be force-stopped through normal means
 * - This makes the Forced Wallpaper Lock more tamper-resistant
 */
object DeviceAdminHelper {

    const val REQUEST_CODE_ENABLE_ADMIN = 9100

    /** The component name for our DeviceAdminReceiver. */
    fun getAdminComponent(context: Context): ComponentName {
        return ComponentName(context, WallShareDeviceAdminReceiver::class.java)
    }

    /** Returns true if WallShare currently has Device Admin privileges. */
    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(getAdminComponent(context))
    }

    /**
     * Creates the Intent to launch the system Device Admin activation prompt.
     */
    fun createAdminPromptIntent(context: Context): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getAdminComponent(context))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "WallShare uses Device Admin to prevent the app from being " +
                        "uninstalled or force-stopped while the Wallpaper Lock is active. " +
                        "You can disable this at any time from settings."
            )
        }
    }

    /**
     * Launches the system Device Admin activation prompt.
     * The user must confirm the activation manually in the system UI.
     *
     * @param activity The activity context to start the intent from.
     */
    fun requestEnableAdmin(activity: Activity) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getAdminComponent(activity))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "WallShare uses Device Admin to prevent the app from being " +
                        "uninstalled or force-stopped while the Wallpaper Lock is active. " +
                        "You can disable this at any time from settings."
            )
        }
        try {
            activity.startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN)
            Log.d(TAG, "Device Admin activation prompt launched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Device Admin activation", e)
        }
    }

    /**
     * Removes Device Admin privileges from WallShare.
     * This must be called before the app can be uninstalled normally.
     */
    fun removeAdmin(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = getAdminComponent(context)
            if (dpm.isAdminActive(component)) {
                dpm.removeActiveAdmin(component)
                Log.i(TAG, "Device Admin removed successfully")
            } else {
                Log.d(TAG, "Device Admin was not active, nothing to remove")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove Device Admin", e)
        }
    }
}

