package com.wallshare.app.wallpaper

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.wallshare.app.WallShareApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "WallpaperCatchupHelper"

/**
 * Handles catching up on the latest received wallpapers when auto-apply is re-enabled.
 * Ensures both the last lockscreen and homescreen wallpapers get set on their respective screens.
 */
object WallpaperCatchupHelper {

    suspend fun catchUpWallpapers(
        context: Context,
        targetScreen: WallpaperTarget? = null
    ) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? WallShareApplication ?: return@withContext
        val container = app.container
        val prefs = container.wallpaperPreferences
        val settings = prefs.getSettings()

        val checkHome = (targetScreen == null || targetScreen == WallpaperTarget.HOME || targetScreen == WallpaperTarget.BOTH) && settings.autoApplyHome
        val checkLock = (targetScreen == null || targetScreen == WallpaperTarget.LOCK || targetScreen == WallpaperTarget.BOTH) && settings.autoApplyLock

        if (!checkHome && !checkLock) return@withContext

        // 1. Catch up HOME screen
        if (checkHome && prefs.hasUnappliedHomeWallpaper()) {
            val reqId = prefs.getLatestReceivedHomeRequestId()
            var file = prefs.getLatestReceivedHomeWallpaperPath()?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
            if (file == null && reqId != null) {
                file = WallpaperStorageHelper.getLocalWallpaperFile(context, reqId).takeIf { it.exists() && it.length() > 0L }
            }

            if (file != null) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val applied = WallpaperStorageHelper.applyWallpaper(context, bitmap, WallpaperTarget.HOME)
                    if (applied && reqId != null) {
                        prefs.recordAppliedWallpaper(WallpaperTarget.HOME, reqId)
                        Log.i(TAG, "Successfully applied latest caught-up HOME wallpaper ($reqId)")
                    }
                }
            }
        }

        // 2. Catch up LOCK screen
        if (checkLock && prefs.hasUnappliedLockWallpaper()) {
            val reqId = prefs.getLatestReceivedLockRequestId()
            var file = prefs.getLatestReceivedLockWallpaperPath()?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
            if (file == null && reqId != null) {
                file = WallpaperStorageHelper.getLocalWallpaperFile(context, reqId).takeIf { it.exists() && it.length() > 0L }
            }

            if (file != null) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val applied = WallpaperStorageHelper.applyWallpaper(context, bitmap, WallpaperTarget.LOCK)
                    if (applied && reqId != null) {
                        prefs.recordAppliedWallpaper(WallpaperTarget.LOCK, reqId)
                        Log.i(TAG, "Successfully applied latest caught-up LOCK wallpaper ($reqId)")
                    }
                }
            }
        }

        // 3. Fallback: Check Wallpaper History from repository if local tracking had no recorded entry
        val homeStillNeeded = checkHome && prefs.getLatestAppliedHomeId() == null
        val lockStillNeeded = checkLock && prefs.getLatestAppliedLockId() == null

        if (homeStillNeeded || lockStillNeeded) {
            try {
                val history = container.wallpaperRepository.getWallpaperHistory(context, limit = 16)
                if (history.isNotEmpty()) {
                    if (homeStillNeeded) {
                        val latestHome = history.firstOrNull { it.target == "HOME" || it.target == "BOTH" }
                        if (latestHome != null && latestHome.id != prefs.getLatestAppliedHomeId()) {
                            val cachedFile = container.wallpaperRepository.downloadAndCacheWallpaper(context, latestHome.id, latestHome.imagePath)
                            if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0L) {
                                val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                                if (bitmap != null && WallpaperStorageHelper.applyWallpaper(context, bitmap, WallpaperTarget.HOME)) {
                                    prefs.recordAppliedWallpaper(WallpaperTarget.HOME, latestHome.id)
                                    Log.i(TAG, "Applied latest HOME wallpaper from history fallback: ${latestHome.id}")
                                }
                            }
                        }
                    }

                    if (lockStillNeeded) {
                        val latestLock = history.firstOrNull { it.target == "LOCK" || it.target == "BOTH" }
                        if (latestLock != null && latestLock.id != prefs.getLatestAppliedLockId()) {
                            val cachedFile = container.wallpaperRepository.downloadAndCacheWallpaper(context, latestLock.id, latestLock.imagePath)
                            if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0L) {
                                val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                                if (bitmap != null && WallpaperStorageHelper.applyWallpaper(context, bitmap, WallpaperTarget.LOCK)) {
                                    prefs.recordAppliedWallpaper(WallpaperTarget.LOCK, latestLock.id)
                                    Log.i(TAG, "Applied latest LOCK wallpaper from history fallback: ${latestLock.id}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "History fallback catch-up encountered non-fatal error", e)
            }
        }
    }
}

