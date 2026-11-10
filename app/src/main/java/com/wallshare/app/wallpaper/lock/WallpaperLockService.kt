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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "WallpaperLockService"
private const val NOTIFICATION_ID = 9001
private const val CHANNEL_ID = "wallshare_lock_enforcer"

/**
 * The Enforcer: A high-resilience Foreground Service that guards the user's wallpaper
 * against unauthorized manual changes while seamlessly accepting incoming wallpapers from friends.
 */
class WallpaperLockService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wallpaperManager: WallpaperManager
    private lateinit var vault: WallpaperLockVault

    private var colorsListener: WallpaperManager.OnColorsChangedListener? = null
    private val enforcementMutex = Mutex()

    companion object {
        @Volatile
        private var selfUpdateUntilTimestamp: Long = 0L

        /** Marks that WallShare is performing a wallpaper change, so color change events within [durationMs] are ignored */
        fun armSelfUpdateWindow(durationMs: Long = 5000L) {
            val newUntil = SystemClock.elapsedRealtime() + durationMs
            if (newUntil > selfUpdateUntilTimestamp) {
                selfUpdateUntilTimestamp = newUntil
            }
            Log.d(TAG, "Self-update window armed for ${durationMs}ms (until=$selfUpdateUntilTimestamp)")
        }

        /** Checks if a color change event occurred during a self-applied wallpaper window */
        fun isSelfUpdateActive(): Boolean {
            val now = SystemClock.elapsedRealtime()
            val active = now < selfUpdateUntilTimestamp
            if (active) {
                Log.d(TAG, "Self-update active (${selfUpdateUntilTimestamp - now}ms remaining) — allowing through")
            }
            return active
        }

        /** Starts the Enforcer Foreground Service */
        fun start(context: Context) {
            val intent = Intent(context, WallpaperLockService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
                Log.d(TAG, "WallpaperLockService start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WallpaperLockService", e)
            }
        }

        /** Stops the Enforcer Foreground Service (called after PIN verified) */
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
         * Call BEFORE applying an incoming wallpaper from a friend.
         * Pre-stages the new image into the vault for the specified screen(s) so it becomes
         * the new locked wallpaper, and arms the self-update window so the enforcer allows all OS callbacks through.
         */
        fun prepareForIncomingWallpaper(
            context: Context,
            imagePath: String,
            which: Int = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        ) {
            armSelfUpdateWindow(8000L)
            Log.i(TAG, "Incoming wallpaper update initiated: $imagePath (which=$which)")
            try {
                val vault = WallpaperLockVault(context)
                val sourceFile = File(imagePath)
                if (sourceFile.exists() && sourceFile.length() > 0) {
                    vault.updateLockedWallpaper(sourceFile, which)
                    Log.d(TAG, "Pre-staged incoming locked wallpaper for which=$which: ${sourceFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-stage incoming locked wallpaper", e)
            }
        }

        /**
         * Call BEFORE applying an incoming bitmap if file path is not cached yet.
         */
        fun prepareForIncomingWallpaperBitmap(
            context: Context,
            bitmap: android.graphics.Bitmap,
            which: Int = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        ) {
            armSelfUpdateWindow(8000L)
            Log.i(TAG, "Incoming wallpaper bitmap update initiated (which=$which)")
            try {
                val vault = WallpaperLockVault(context)
                vault.updateLockedWallpaperBitmap(bitmap, which)
                Log.d(TAG, "Pre-staged incoming locked wallpaper bitmap for which=$which")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-stage incoming locked wallpaper bitmap", e)
            }
        }

        /**
         * Call AFTER applying a wallpaper from WallShare to settle and persist in vault.
         */
        fun notifySelfWallpaperApplied(
            context: Context,
            imagePath: String? = null,
            which: Int = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        ) {
            // Keep window armed for 2500ms after setBitmap completes to catch any trailing OS callbacks
            armSelfUpdateWindow(2500L)
            try {
                val vault = WallpaperLockVault(context)
                if (imagePath != null) {
                    val sourceFile = File(imagePath)
                    if (sourceFile.exists() && sourceFile.length() > 0) {
                        vault.updateLockedWallpaper(sourceFile, which)
                    }
                }
                val wm = WallpaperManager.getInstance(context)
                if ((which and WallpaperManager.FLAG_SYSTEM) != 0) {
                    val sysId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                    if (sysId != -1) vault.saveLastAppliedSystemId(sysId)
                }
                if ((which and WallpaperManager.FLAG_LOCK) != 0) {
                    val lockId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK)
                    if (lockId != -1) vault.saveLastAppliedLockId(lockId)
                }
                Log.i(TAG, "Self-applied wallpaper synced with lock vault (which=$which)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync self-applied wallpaper to vault", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        vault = WallpaperLockVault(applicationContext)
        wallpaperManager = WallpaperManager.getInstance(applicationContext)

        createNotificationChannel()
        startServiceForeground()
        registerWallpaperListener()
        checkWallpaperIntegrityOnStart()
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
        checkWallpaperIntegrityOnStart()

        return START_STICKY
    }

    private fun checkWallpaperIntegrityOnStart() {
        if (!vault.isLockActive()) return
        serviceScope.launch {
            try {
                val lastSysId = vault.getLastAppliedSystemId()
                val lastLockId = vault.getLastAppliedLockId()
                val curSysId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                val curLockId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)

                var whichToEnforce = 0
                if (lastSysId != -1 && curSysId != -1 && curSysId != lastSysId) {
                    Log.w(TAG, "Home wallpaper changed while inactive (stored=$lastSysId, current=$curSysId)")
                    whichToEnforce = whichToEnforce or WallpaperManager.FLAG_SYSTEM
                }
                if (lastLockId != -1 && curLockId != -1 && curLockId != lastLockId) {
                    Log.w(TAG, "Lock wallpaper changed while inactive (stored=$lastLockId, current=$curLockId)")
                    whichToEnforce = whichToEnforce or WallpaperManager.FLAG_LOCK
                }

                if (whichToEnforce != 0) {
                    enforceLockedWallpaper(whichToEnforce)
                } else {
                    if (lastSysId == -1 && curSysId != -1) vault.saveLastAppliedSystemId(curSysId)
                    if (lastLockId == -1 && curLockId != -1) vault.saveLastAppliedLockId(curLockId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Startup integrity check failed", e)
            }
        }
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
                checkAndEnforceWallpaper(which, "colors_changed_listener")
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

    private fun checkAndEnforceWallpaper(which: Int, triggerReason: String) {
        if (!vault.isLockActive()) {
            Log.d(TAG, "Lock inactive, ignoring check and stopping service")
            stopSelf()
            return
        }

        // If WallShare is currently applying a wallpaper or within the settling window, allow it through
        if (isSelfUpdateActive()) {
            Log.d(TAG, "Wallpaper change was self-applied by WallShare ($triggerReason, which=$which) — allowing update through")
            return
        }

        // Otherwise, it is an UNAUTHORIZED manual change by the user! Revert immediately!
        Log.w(TAG, "Unauthorized manual wallpaper change detected ($triggerReason, which=$which)! Enforcing locked wallpaper...")
        enforceLockedWallpaper(which)
    }

    private fun enforceLockedWallpaper(whichToEnforce: Int = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) {
        serviceScope.launch {
            enforcementMutex.withLock {
                // If a self-update was armed while waiting for the lock, do not revert
                if (isSelfUpdateActive()) {
                    Log.d(TAG, "Self-update active upon acquiring enforcement mutex — skipping revert")
                    return@launch
                }

                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "WallShare::LockEnforcerRevert"
                )

                try {
                    wakeLock.acquire(15_000L)

                    val lockedFiles = vault.ensureLockedWallpapersReady()
                    val homeFile = lockedFiles.homeFile
                    val lockFile = lockedFiles.lockFile

                    // Arm window for 2500ms so our own revert does not re-trigger enforcement or loop
                    armSelfUpdateWindow(2500L)

                    var targetWhich = whichToEnforce
                    if (targetWhich == 0) {
                        val curSysId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                        val curLockId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
                        val lastSysId = vault.getLastAppliedSystemId()
                        val lastLockId = vault.getLastAppliedLockId()
                        if (lastSysId != -1 && curSysId != lastSysId) {
                            targetWhich = targetWhich or WallpaperManager.FLAG_SYSTEM
                        }
                        if (lastLockId != -1 && curLockId != lastLockId) {
                            targetWhich = targetWhich or WallpaperManager.FLAG_LOCK
                        }
                        if (targetWhich == 0) {
                            targetWhich = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                        }
                    }

                    val enforceHome = (targetWhich and WallpaperManager.FLAG_SYSTEM) != 0
                    val enforceLock = (targetWhich and WallpaperManager.FLAG_LOCK) != 0

                    if (enforceHome && enforceLock && homeFile != null && lockFile != null && homeFile.absolutePath == lockFile.absolutePath) {
                        val bitmap = BitmapFactory.decodeFile(homeFile.absolutePath)
                        if (bitmap != null) {
                            val targets = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                            wallpaperManager.setBitmap(bitmap, null, true, targets)
                            vault.saveLastAppliedSystemId(wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM))
                            vault.saveLastAppliedLockId(wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK))
                            Log.i(TAG, "Locked wallpaper restored to both screens against unauthorized manual change!")
                        }
                    } else {
                        // Revert screens independently with their respective wallpapers!
                        if (enforceHome && homeFile != null && homeFile.exists() && homeFile.length() > 0L) {
                            val homeBitmap = BitmapFactory.decodeFile(homeFile.absolutePath)
                            if (homeBitmap != null) {
                                wallpaperManager.setBitmap(homeBitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                                vault.saveLastAppliedSystemId(wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM))
                                Log.i(TAG, "Locked HOME wallpaper restored successfully against unauthorized manual change!")
                            } else {
                                Log.e(TAG, "Failed to decode locked home wallpaper file: ${homeFile.absolutePath}")
                            }
                        }

                        if (enforceLock && lockFile != null && lockFile.exists() && lockFile.length() > 0L) {
                            val lockBitmap = BitmapFactory.decodeFile(lockFile.absolutePath)
                            if (lockBitmap != null) {
                                wallpaperManager.setBitmap(lockBitmap, null, true, WallpaperManager.FLAG_LOCK)
                                vault.saveLastAppliedLockId(wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK))
                                Log.i(TAG, "Locked LOCK wallpaper restored successfully against unauthorized manual change!")
                            } else {
                                Log.e(TAG, "Failed to decode locked lock wallpaper file: ${lockFile.absolutePath}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in enforceLockedWallpaper", e)
                } finally {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                }
            }
        }
    }

    private fun resurrectService() {
        try {
            val broadcastIntent = Intent(applicationContext, WallpaperLockReviverReceiver::class.java).apply {
                action = WallpaperLockReviverReceiver.ACTION_REVIVE_LOCK
            }
            sendBroadcast(broadcastIntent)

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
