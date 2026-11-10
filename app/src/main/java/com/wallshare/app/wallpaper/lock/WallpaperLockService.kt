package com.wallshare.app.wallpaper.lock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
private const val POLL_INTERVAL_MS = 2000L

/**
 * The Enforcer: A high-resilience Foreground Service that guards the user's wallpaper
 * against unauthorized manual changes while seamlessly accepting incoming wallpapers from friends.
 *
 * Uses THREE independent detection layers:
 * 1. OnColorsChangedListener (API 27+) — fastest, fires on wallpaper color changes
 * 2. ACTION_WALLPAPER_CHANGED BroadcastReceiver — reliable system broadcast
 * 3. Periodic wallpaper ID polling (every 2s) — last-resort safety net
 */
class WallpaperLockService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wallpaperManager: WallpaperManager
    private lateinit var vault: WallpaperLockVault

    private var colorsListener: WallpaperManager.OnColorsChangedListener? = null
    private var wallpaperChangedReceiver: BroadcastReceiver? = null
    private val enforcementMutex = Mutex()
    private var pollingRunnable: Runnable? = null

    @Volatile
    private var cachedHomeBitmap: android.graphics.Bitmap? = null
    @Volatile
    private var cachedLockBitmap: android.graphics.Bitmap? = null

    fun clearBitmapCache() {
        cachedHomeBitmap = null
        cachedLockBitmap = null
    }

    private fun getOrDecodeHomeBitmap(file: File): android.graphics.Bitmap? {
        val cached = cachedHomeBitmap
        if (cached != null && !cached.isRecycled) return cached
        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.also { cachedHomeBitmap = it }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to decode home bitmap from ${file.absolutePath}", e)
            null
        }
    }

    private fun getOrDecodeLockBitmap(file: File): android.graphics.Bitmap? {
        val cached = cachedLockBitmap
        if (cached != null && !cached.isRecycled) return cached
        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.also { cachedLockBitmap = it }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to decode lock bitmap from ${file.absolutePath}", e)
            null
        }
    }

    companion object {
        @Volatile
        private var instance: WallpaperLockService? = null

        @Volatile
        private var selfUpdateUntilTimestamp: Long = 0L

        /** Marks that WallShare is performing a friend wallpaper change, so events within [durationMs] are ignored */
        fun armSelfUpdateWindow(durationMs: Long = 2000L) {
            val newUntil = SystemClock.elapsedRealtime() + durationMs
            if (newUntil > selfUpdateUntilTimestamp) {
                selfUpdateUntilTimestamp = newUntil
            }
            Log.d(TAG, "Self-update window armed for ${durationMs}ms (until=$selfUpdateUntilTimestamp)")
        }

        /** Checks if a color change event occurred during an incoming friend wallpaper window */
        fun isSelfUpdateActive(): Boolean {
            val now = SystemClock.elapsedRealtime()
            val active = now < selfUpdateUntilTimestamp
            if (active) {
                Log.d(TAG, "Self-update active (${selfUpdateUntilTimestamp - now}ms remaining) — allowing through")
            }
            return active
        }

        fun invalidateBitmapCache() {
            instance?.clearBitmapCache()
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
                invalidateBitmapCache()
                Log.i(TAG, "Self-applied wallpaper synced with lock vault (which=$which)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync self-applied wallpaper to vault", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        vault = WallpaperLockVault(applicationContext)
        wallpaperManager = WallpaperManager.getInstance(applicationContext)

        createNotificationChannel()
        startServiceForeground()
        registerWallpaperListener()
        registerWallpaperChangedReceiver()
        startWallpaperIdPolling()
        checkWallpaperIntegrityOnStart()
        Log.i(TAG, "WallpaperLockService created — 3-layer enforcer active (colors listener + broadcast + polling)")
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
        stopWallpaperIdPolling()
        unregisterWallpaperChangedReceiver()
        unregisterWallpaperListener()
        clearBitmapCache()
        if (instance == this) instance = null
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

    // ─── Layer 2: ACTION_WALLPAPER_CHANGED BroadcastReceiver ───────────────────

    private fun registerWallpaperChangedReceiver() {
        wallpaperChangedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "ACTION_WALLPAPER_CHANGED broadcast received")
                val both = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                checkAndEnforceWallpaper(both, "wallpaper_changed_broadcast")
            }
        }
        try {
            val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wallpaperChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(wallpaperChangedReceiver, filter)
            }
            Log.d(TAG, "ACTION_WALLPAPER_CHANGED receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register ACTION_WALLPAPER_CHANGED receiver", e)
        }
    }

    private fun unregisterWallpaperChangedReceiver() {
        wallpaperChangedReceiver?.let {
            try {
                unregisterReceiver(it)
                wallpaperChangedReceiver = null
                Log.d(TAG, "ACTION_WALLPAPER_CHANGED receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister wallpaper changed receiver", e)
            }
        }
    }

    // ─── Layer 3: Periodic Wallpaper ID Polling ────────────────────────────────

    private fun startWallpaperIdPolling() {
        stopWallpaperIdPolling()
        pollingRunnable = object : Runnable {
            override fun run() {
                if (!vault.isLockActive()) {
                    Log.d(TAG, "Polling: Lock no longer active, stopping poll cycle")
                    return
                }
                pollWallpaperIds()
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(pollingRunnable!!, POLL_INTERVAL_MS)
        Log.d(TAG, "Wallpaper ID polling started (every ${POLL_INTERVAL_MS}ms)")
    }

    private fun stopWallpaperIdPolling() {
        pollingRunnable?.let {
            mainHandler.removeCallbacks(it)
            pollingRunnable = null
            Log.d(TAG, "Wallpaper ID polling stopped")
        }
    }

    private fun pollWallpaperIds() {
        try {
            if (isSelfUpdateActive()) return

            val curSysId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            val curLockId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
            val lastSysId = vault.getLastAppliedSystemId()
            val lastLockId = vault.getLastAppliedLockId()

            var whichChanged = 0
            if (lastSysId != -1 && curSysId != -1 && curSysId != lastSysId) {
                whichChanged = whichChanged or WallpaperManager.FLAG_SYSTEM
            } else if (lastSysId == -1 && curSysId != -1) {
                vault.saveLastAppliedSystemId(curSysId)
            }

            if (lastLockId != -1 && curLockId != -1 && curLockId != lastLockId) {
                whichChanged = whichChanged or WallpaperManager.FLAG_LOCK
            } else if (lastLockId == -1 && curLockId != -1) {
                vault.saveLastAppliedLockId(curLockId)
            }

            if (whichChanged != 0) {
                Log.w(TAG, "Polling detected wallpaper ID mismatch! sys(stored=$lastSysId, current=$curSysId) lock(stored=$lastLockId, current=$curLockId)")
                enforceLockedWallpaper(whichChanged)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during wallpaper ID poll", e)
        }
    }

    private fun checkAndEnforceWallpaper(which: Int, triggerReason: String) {
        if (!vault.isLockActive()) {
            Log.d(TAG, "Lock inactive, ignoring check and stopping service")
            stopSelf()
            return
        }

        // If WallShare is currently applying an incoming wallpaper from a friend, allow it through
        if (isSelfUpdateActive()) {
            Log.d(TAG, "Incoming friend update in progress ($triggerReason, which=$which) — allowing through")
            return
        }

        val curSysId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        val curLockId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
        val lastSysId = vault.getLastAppliedSystemId()
        val lastLockId = vault.getLastAppliedLockId()

        var whichToEnforce = 0
        if (lastSysId != -1 && curSysId != -1 && curSysId != lastSysId) {
            whichToEnforce = whichToEnforce or WallpaperManager.FLAG_SYSTEM
        }
        if (lastLockId != -1 && curLockId != -1 && curLockId != lastLockId) {
            whichToEnforce = whichToEnforce or WallpaperManager.FLAG_LOCK
        }

        // If the current IDs already match the vault, this event was our own setBitmap
        // or a harmless callback — do NOT loop or re-revert
        if (whichToEnforce == 0 && lastSysId != -1 && lastLockId != -1) {
            Log.d(TAG, "Wallpaper IDs match locked vault (sys=$curSysId, lock=$curLockId) — no revert needed ($triggerReason)")
            return
        }

        val finalWhich = if (whichToEnforce != 0) whichToEnforce else which
        Log.w(TAG, "⚠ UNAUTHORIZED wallpaper change detected ($triggerReason, which=$which, target=$finalWhich)! Enforcing locked wallpaper...")
        enforceLockedWallpaper(finalWhich)
    }

    private fun enforceLockedWallpaper(whichToEnforce: Int = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) {
        serviceScope.launch {
            enforcementMutex.withLock {
                if (!vault.isLockActive()) {
                    Log.d(TAG, "Lock inactive upon acquiring enforcement mutex — skipping revert")
                    return@launch
                }

                // If a friend wallpaper is actively being downloaded/applied right now by WallShare, don't interrupt it
                if (isSelfUpdateActive()) {
                    Log.d(TAG, "Incoming friend update actively in progress — skipping revert")
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

                    if (homeFile == null && lockFile == null) {
                        Log.e(TAG, "CRITICAL: No locked wallpaper files available! Cannot enforce.")
                        return@launch
                    }

                    var targetWhich = whichToEnforce
                    val curSysId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                    val curLockId = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
                    val lastSysId = vault.getLastAppliedSystemId()
                    val lastLockId = vault.getLastAppliedLockId()

                    if (targetWhich == 0) {
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

                    // Loop up to 3 times: guarantees that rapid consecutive overwrites are immediately caught and reverted
                    var attempts = 0
                    var verified = false

                    while (attempts < 3 && !verified) {
                        attempts++

                        if (enforceHome && enforceLock && homeFile != null && lockFile != null && homeFile.absolutePath == lockFile.absolutePath) {
                            val bitmap = getOrDecodeHomeBitmap(homeFile)
                            if (bitmap != null) {
                                val targets = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                                wallpaperManager.setBitmap(bitmap, null, true, targets)
                                val newSys = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                                val newLock = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
                                if (newSys != -1) vault.saveLastAppliedSystemId(newSys)
                                if (newLock != -1) vault.saveLastAppliedLockId(newLock)
                                Log.i(TAG, "Locked wallpaper restored to both screens (attempt $attempts, sys=$newSys, lock=$newLock)")
                            }
                        } else {
                            if (enforceHome && homeFile != null && homeFile.exists() && homeFile.length() > 0L) {
                                val homeBitmap = getOrDecodeHomeBitmap(homeFile)
                                if (homeBitmap != null) {
                                    wallpaperManager.setBitmap(homeBitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                                    val newSys = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                                    if (newSys != -1) vault.saveLastAppliedSystemId(newSys)
                                    Log.i(TAG, "Locked HOME wallpaper restored (attempt $attempts, sys=$newSys)")
                                }
                            }

                            if (enforceLock && lockFile != null && lockFile.exists() && lockFile.length() > 0L) {
                                val lockBitmap = getOrDecodeLockBitmap(lockFile)
                                if (lockBitmap != null) {
                                    wallpaperManager.setBitmap(lockBitmap, null, true, WallpaperManager.FLAG_LOCK)
                                    val newLock = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
                                    if (newLock != -1) vault.saveLastAppliedLockId(newLock)
                                    Log.i(TAG, "Locked LOCK wallpaper restored (attempt $attempts, lock=$newLock)")
                                }
                            }
                        }

                        // Immediate post-revert verification check
                        val verifySys = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                        val verifyLock = wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
                        val sysOk = !enforceHome || (verifySys != -1 && verifySys == vault.getLastAppliedSystemId())
                        val lockOk = !enforceLock || (verifyLock != -1 && verifyLock == vault.getLastAppliedLockId())

                        if (sysOk && lockOk) {
                            verified = true
                            Log.i(TAG, "Wallpaper lock enforcement successfully verified on screen!")
                        } else {
                            Log.w(TAG, "Immediate overwrite detected during enforcement (sysOk=$sysOk, lockOk=$lockOk). Retrying enforcement...")
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
