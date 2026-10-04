package com.wallshare.app

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.wallshare.app.wallpaper.WallpaperPollWorker

class WallShareApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        schedulePollWorker()
    }

    /**
     * Kicks off the first poll immediately. After it runs, the worker
     * reschedules itself with a delay — creating a perpetual polling loop
     * that survives app kills and reboots.
     *
     * This is the FALLBACK behind WallpaperForegroundService (which does
     * its own, more frequent polling). If the foreground service is alive,
     * this poll rarely finds anything to do, but it covers the edge case
     * where an aggressive OEM battery skin kills the foreground service.
     *
     * REPLACE policy ensures opening the app always resets the poll timer,
     * preventing a stale delayed poll from the previous cycle from lingering.
     */
    private fun schedulePollWorker() {
        val initialPoll = OneTimeWorkRequestBuilder<WallpaperPollWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            WallpaperPollWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            initialPoll,
        )

        Log.d("WallShareApp", "Wallpaper poll worker scheduled")
    }
}