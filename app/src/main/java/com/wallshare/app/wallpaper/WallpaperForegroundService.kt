package com.wallshare.app.wallpaper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wallshare.app.R
import com.wallshare.app.WallShareApplication
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "WallpaperService"
private const val CHANNEL_ID = "wallshare_bg"
private const val DEBUG_CHANNEL_ID = "wallshare_debug"
private const val NOTIFICATION_ID = 1
private const val DEBUG_NOTIFICATION_ID = 2
// TODO: Change back to 15 * 60 * 1000L (15 minutes) after testing
private const val POLL_INTERVAL_MS = 60_000L // 1 minute for testing

/**
 * A foreground service that keeps the app process alive and periodically
 * polls Supabase for pending wallpaper requests.
 *
 * Key survival mechanisms:
 *   - START_STICKY: OS recreates the service after a memory kill
 *   - stopWithTask=false (manifest): survives recents swipe
 *   - onTaskRemoved: re-starts itself if swiped
 *   - PARTIAL_WAKE_LOCK: CPU stays on during poll cycles
 */
class WallpaperForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollCount = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate — starting foreground")

        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))

        acquireWakeLock()
        startPolling()
        Log.d(TAG, "Service started successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed from recents — scheduling restart")
        val restartIntent = Intent(applicationContext, WallpaperForegroundService::class.java)
        startForegroundService(restartIntent)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── WakeLock ──────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WallShare::BackgroundPoll"
        ).apply { acquire() }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ── Polling Loop ─────────────────────────────────────────────────

    private fun startPolling() {
        serviceScope.launch {
            // Short initial delay so the app finishes auth initialization first
            delay(5_000L)

            while (true) {
                pollCount++
                try {
                    pollForPendingRequests()
                } catch (e: Exception) {
                    val msg = "Poll #$pollCount CRASHED: ${e.message?.take(60)}"
                    Log.w(TAG, msg, e)
                    showDebugNotification(msg)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollForPendingRequests() {
        Log.d(TAG, "Poll #$pollCount starting...")
        showDebugNotification("Poll #$pollCount starting…")

        val container = (application as WallShareApplication).container
        val auth = container.supabase.auth

        // ── 1. Wait for auth init (with timeout) ────────────────────
        val initOk = withTimeoutOrNull(15_000L) {
            auth.awaitInitialization()
            true
        }
        if (initOk == null) {
            showDebugNotification("Poll #$pollCount: auth init TIMEOUT (15s)")
            return
        }

        // ── 2. Force-refresh the session on EVERY poll cycle ────────
        // This is the simplest way to guarantee a fresh access token.
        // It costs one extra HTTP call per minute but eliminates all
        // token-expiry issues in the background.
        val sessionStatus = auth.sessionStatus.value
        Log.d(TAG, "Poll #$pollCount session status: $sessionStatus")

        try {
            when (sessionStatus) {
                is SessionStatus.Authenticated -> {
                    // Session looks valid — refresh anyway to guarantee fresh token
                    auth.refreshCurrentSession()
                    Log.d(TAG, "Poll #$pollCount: session refreshed")
                }
                is SessionStatus.NotAuthenticated,
                is SessionStatus.RefreshFailure -> {
                    // Session expired — try to restore from refresh token
                    auth.refreshCurrentSession()
                    Log.d(TAG, "Poll #$pollCount: session restored from refresh token")
                }
                is SessionStatus.Initializing -> {
                    showDebugNotification("Poll #$pollCount: still initializing")
                    return
                }
            }
        } catch (e: Exception) {
            val msg = "Poll #$pollCount: refresh FAILED — ${e.message?.take(50)}"
            Log.w(TAG, msg, e)
            showDebugNotification(msg)
            return
        }

        val currentUser = auth.currentUserOrNull()
        if (currentUser == null) {
            showDebugNotification("Poll #$pollCount: no user after refresh")
            return
        }

        Log.d(TAG, "Poll #$pollCount: authenticated as ${currentUser.id.take(8)}…")

        // ── 3. Fetch pending requests ────────────────────────────────
        val pendingRequests = try {
            container.wallpaperRepository.getPendingRequests()
        } catch (e: Exception) {
            val msg = "Poll #$pollCount: fetch FAILED — ${e.message?.take(50)}"
            Log.e(TAG, msg, e)
            showDebugNotification(msg)
            return
        }

        if (pendingRequests.isEmpty()) {
            showDebugNotification("Poll #$pollCount: ✓ no pending (user=${currentUser.id.take(8)})")
            return
        }

        showDebugNotification("Poll #$pollCount: found ${pendingRequests.size} — applying…")

        // ── 4. Process each request inline ───────────────────────────
        for (request in pendingRequests) {
            try {
                val wallpaperRepository = container.wallpaperRepository

                if (request.status == "PENDING") {
                    wallpaperRepository.updateStatus(request.id, "PROCESSING")
                }

                val imageBytes = wallpaperRepository.downloadImage(request.image_path)

                val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                    imageBytes, 0, imageBytes.size
                )
                if (bitmap == null) {
                    Log.e(TAG, "Could not decode image for request ${request.id}")
                    wallpaperRepository.updateStatus(request.id, "FAILED")
                    continue
                }

                val wallpaperManager = android.app.WallpaperManager.getInstance(this)
                val which = when (request.target) {
                    "HOME" -> android.app.WallpaperManager.FLAG_SYSTEM
                    "LOCK" -> android.app.WallpaperManager.FLAG_LOCK
                    "BOTH" -> android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK
                    else -> {
                        wallpaperRepository.updateStatus(request.id, "FAILED")
                        continue
                    }
                }
                wallpaperManager.setBitmap(bitmap, null, true, which)

                wallpaperRepository.updateStatus(request.id, "COMPLETED")
                showDebugNotification("Poll #$pollCount: ✓ WALLPAPER APPLIED!")
                Log.d(TAG, "Wallpaper request ${request.id} applied successfully!")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to process request ${request.id}", e)
                showDebugNotification("Poll #$pollCount: apply FAILED — ${e.message?.take(50)}")
            }
        }
    }

    // ── Notifications ────────────────────────────────────────────────

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        // Low-priority channel for the required foreground service notification
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "WallShare Background",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps WallShare running to receive wallpapers"
                setShowBadge(false)
            }
        )

        // Higher-priority channel for debug/status notifications that MUST be visible.
        // TODO: Remove this channel or lower priority once background polling is confirmed working.
        manager.createNotificationChannel(
            NotificationChannel(
                DEBUG_CHANNEL_ID,
                "WallShare Poll Status",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Shows what the background poller is doing"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WallShare")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Posts a separate, visible notification showing poll status.
     * Uses IMPORTANCE_DEFAULT so it shows as a normal notification,
     * not grouped under "Active apps" like the foreground one.
     *
     * TODO: Remove once background polling is confirmed working.
     */
    private fun showDebugNotification(statusText: String) {
        val notification = NotificationCompat.Builder(this, DEBUG_CHANNEL_ID)
            .setContentTitle("WallShare Poll")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(DEBUG_NOTIFICATION_ID, notification)
    }

    companion object {
        fun start(context: Context) {
            Log.d(TAG, "start() called — launching foreground service")
            try {
                val intent = Intent(context, WallpaperForegroundService::class.java)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }
    }
}
