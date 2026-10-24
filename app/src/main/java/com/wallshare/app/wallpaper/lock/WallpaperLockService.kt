package com.wallshare.app.wallpaper.lock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wallshare.app.MainActivity
import com.wallshare.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "WallpaperLockService"
private const val NOTIFICATION_ID = 9001
private const val CHANNEL_ID = "wallshare_lock_enforcer"

/**
 * The Enforcer: A high-resilience Foreground Service that protects the user's wallpaper
 * against manual or external changes.
 *
 * Key guarantees:
 * 1. Rapid-Change Immunity: Compares system wallpaper IDs via [WallpaperManager.getWallpaperId]
 *    rather than relying on a blind debounce window. If a user quickly changes the wallpaper
 *    multiple times, the enforcement loop continues until the active wallpaper ID matches
 *    the locked wallpaper ID.
 * 2. Reviver & Anti-Swipe Protection: Uses persistent notification flags ([Notification.FLAG_NO_CLEAR],
 *    [Notification.FLAG_ONGOING_EVENT], [NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE]) and
 *    automatically resurrects itself via [AlarmManager] and [WallpaperLockReviverReceiver] if
 *    destroyed without valid PIN verification.
 */
class WallpaperLockService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wallpaperManager: WallpaperManager
    private lateinit var vault: WallpaperLockVault

    private var colorsListener: WallpaperManager.OnColorsChangedListener? = null
    private val enforcementMutex = Mutex()
    private val isEnforcing = AtomicBoolean(false)

    companion object {
        @Volatile
        private var lastAppliedSystemId: Int = -1
        @Volatile
        private var lastAppliedLockId: Int = -1
        private val isIncomingUpdateActive = AtomicBoolean(false)

        /**
         * Starts the Enforcer Foreground Service.
         */
        fun start(context: Context) {
            val intent = Intent(context, WallpaperLockService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
                Log.d(TAG, "WallpaperLockService start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WallpaperLockService", e)
            }
        }

        /**
         * Stops the Enforcer Foreground Service (called after PIN verified).
         */
        fun stop(context: Context) {
            val intent = Intent(context, WallpaperLockService::class.java)
            try {
                context.stopService(intent)
                Log.d(TAG, "WallpaperLockService stop requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop WallpaperLockService", e)
            }
        }

        /**
         * Call BEFORE applying an incoming wallpaper from a friend or manual history selection.
         * Pre-stages the new image into the vault so it becomes the new locked wallpaper, and
         * temporarily pauses the enforcer so the incoming wallpaper is allowed through.
         */
        fun prepareForIncomingWallpaper(context: Context, imagePath: String) {
            isIncomingUpdateActive.set(true)
            Log.i(TAG, "Incoming wallpaper update initiated: $imagePath")
            try {
                val vault = WallpaperLockVault(context)
                val sourceFile = File(imagePath)
                if (sourceFile.exists() && sourceFile.length() > 0) {
                    val lockedFile = vault.getLockedWallpaperFile()
                    sourceFile.copyTo(lockedFile, overwrite = true)
                    vault.saveLatestWallpaperPath(lockedFile.absolutePath)
                    Log.d(TAG, "Pre-staged incoming locked wallpaper: ${lockedFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-stage incoming locked wallpaper", e)
            }

            // Failsafe timeout so the flag never gets stuck if caller crashes
            Handler(Looper.getMainLooper()).postDelayed({
                isIncomingUpdateActive.set(false)
            }, 10_000L)
        }

        /**
         * Call AFTER applying an incoming wallpaper from WallShare.
         * Updates the expected wallpaper IDs to the newly applied wallpaper.
         */
        fun notifySelfWallpaperApplied(context: Context, imagePath: String? = null) {
            try {
                if (imagePath != null) {
                    val vault = WallpaperLockVault(context)
                    val sourceFile = File(imagePath)
                    if (sourceFile.exists() && sourceFile.length() > 0) {
                        val lockedFile = vault.getLockedWallpaperFile()
                        sourceFile.copyTo(lockedFile, overwrite = true)
                        vault.saveLatestWallpaperPath(lockedFile.absolutePath)
                    }
                }

                // Update expected wallpaper IDs immediately
                val wm = WallpaperManager.getInstance(context)
                lastAppliedSystemId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                lastAppliedLockId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK)
                val vault = WallpaperLockVault(context)
                vault.saveLastAppliedSystemId(lastAppliedSystemId)
                vault.saveLastAppliedLockId(lastAppliedLockId)
                Log.i(TAG, "Incoming wallpaper applied successfully! New locked IDs: sys=$lastAppliedSystemId, lock=$lastAppliedLockId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync self-applied wallpaper to vault", e)
            } finally {
                // Keep the incoming update flag active briefly for OS color events to settle
                Handler(Looper.getMainLooper()).postDelayed({
                    isIncomingUpdateActive.set(false)
                    Log.d(TAG, "Lock enforcement re-armed with new locked wallpaper")
                }, 1500L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        vault = WallpaperLockVault(applicationContext)
        wallpaperManager = WallpaperManager.getInstance(applicationContext)

        if (lastAppliedSystemId == -1) {
            lastAppliedSystemId = vault.getLastAppliedSystemId()
            lastAppliedLockId = vault.getLastAppliedLockId()
        }

        createNotificationChannel()
        startServiceForeground()
        registerWallpaperListener()
        Log.i(TAG, "WallpaperLockService created and enforcer active")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!vault.isLockActive()) {
            Log.w(TAG, "Service started but lock is inactive. Stopping self.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Re-affirm foreground notification
        startServiceForeground()

        // Check and enforce immediately upon start
        checkAndEnforceWallpaper("service_start_command")

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (vault.isLockActive()) {
            Log.w(TAG, "App task removed while lock active — triggering resurrection")
            resurrectService()
        }
    }

    override fun onDestroy() {
        unregisterWallpaperListener()
        serviceScope.cancel()

        // If the service was destroyed while the lock is STILL active (e.g. user swiped notification,
        // killed process, or OS memory pressure), immediately resurrect!
        if (vault.isLockActive()) {
            Log.w(TAG, "WallpaperLockService destroyed while lock is ACTIVE! Reviving immediately...")
            resurrectService()
        } else {
            Log.i(TAG, "WallpaperLockService destroyed cleanly with lock inactive")
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerWallpaperListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            colorsListener = WallpaperManager.OnColorsChangedListener { _, which ->
                Log.d(TAG, "OnColorsChangedListener triggered (which=$which)")
                checkAndEnforceWallpaper("colors_changed_listener")
            }
            try {
                wallpaperManager.addOnColorsChangedListener(colorsListener!!, mainHandler)
                Log.d(TAG, "Successfully registered addOnColorsChangedListener")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register addOnColorsChangedListener", e)
            }
        }
    }

    private fun unregisterWallpaperListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && colorsListener != null) {
            try {
                wallpaperManager.removeOnColorsChangedListener(colorsListener!!)
                colorsListener = null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister addOnColorsChangedListener", e)
            }
        }
    }

    /**
     * Verifies the active wallpaper against the locked wallpaper ID.
     * Uses a retry loop to ensure that even rapid successive manual changes are immediately
     * caught and overwritten without getting stuck.
     */
    private fun checkAndEnforceWallpaper(triggerReason: String) {
        if (!vault.isLockActive()) {
            Log.d(TAG, "Lock inactive, ignoring check and stopping service")
            stopSelf()
            return
        }

        // If an incoming wallpaper from WallShare is currently being applied, do not revert it!
        if (isIncomingUpdateActive.get()) {
            Log.d(TAG, "Incoming WallShare wallpaper update in progress ($triggerReason) — allowing update through")
            return
        }

        serviceScope.launch {
            enforcementMutex.withLock {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "WallShare::LockEnforcerCheck"
                )

                try {
                    wakeLock.acquire(15_000L)

                    var loopCount = 0
                    val maxLoops = 6

                    while (loopCount < maxLoops) {
                        loopCount++

                        val currentSystemId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                        val currentLockId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)

                        val isSystemMatch = (lastAppliedSystemId > 0 && currentSystemId == lastAppliedSystemId)
                        val isLockMatch = (lastAppliedLockId <= 0 || currentLockId <= 0 || currentLockId == lastAppliedLockId)

                        if (isSystemMatch && isLockMatch) {
                            Log.d(TAG, "Active wallpaper IDs match locked state (sys=$currentSystemId, lock=$currentLockId). No enforcement needed.")
                            break
                        }

                        Log.w(TAG, "Unauthorized wallpaper state detected ($triggerReason, pass $loopCount)! " +
                                "Current IDs: (sys=$currentSystemId, lock=$currentLockId) vs " +
                                "Expected IDs: (sys=$lastAppliedSystemId, lock=$lastAppliedLockId). Overwriting now!")

                        val wallpaperFile = vault.ensureLockedWallpaperReady()
                        if (wallpaperFile == null || !wallpaperFile.exists() || wallpaperFile.length() == 0L) {
                            Log.e(TAG, "No valid locked wallpaper file found in vault")
                            break
                        }

                        val bitmap = BitmapFactory.decodeFile(wallpaperFile.absolutePath)
                        if (bitmap == null) {
                            Log.e(TAG, "Failed to decode locked wallpaper file: ${wallpaperFile.absolutePath}")
                            break
                        }

                        // Apply to both Home and Lock screens
                        val targets = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                        wallpaperManager.setBitmap(bitmap, null, true, targets)

                        // Immediately update our expected IDs to the newly minted IDs
                        lastAppliedSystemId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                        lastAppliedLockId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
                        vault.saveLastAppliedSystemId(lastAppliedSystemId)
                        vault.saveLastAppliedLockId(lastAppliedLockId)
                        Log.i(TAG, "Locked wallpaper restored successfully. New expected IDs: (sys=$lastAppliedSystemId, lock=$lastAppliedLockId)")

                        // Brief delay for system write to settle before checking if another change was queued
                        delay(250L)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in checkAndEnforceWallpaper", e)
                } finally {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                }
            }
        }
    }

    /**
     * Resurrects the service using both a direct Broadcast to [WallpaperLockReviverReceiver]
     * and a scheduled [AlarmManager] wakeup.
     */
    private fun resurrectService() {
        try {
            // 1. Broadcast revival
            val broadcastIntent = Intent(applicationContext, WallpaperLockReviverReceiver::class.java).apply {
                action = WallpaperLockReviverReceiver.ACTION_REVIVE_LOCK
            }
            sendBroadcast(broadcastIntent)

            // 2. AlarmManager scheduled revival (safety net against deep OS task kill)
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                9002,
                broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + 1000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            }
            Log.d(TAG, "Revival mechanisms scheduled successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule revival", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wallpaper Lock Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Guards your wallpaper against unauthorized changes"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startServiceForeground() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wallpaper Lock Active")
            .setContentText("WallShare is protecting your wallpaper (PIN protected)")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .build()
            .apply {
                // Hard ongoing flags to prevent swipe-to-dismiss on OEM notification shades
                flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
