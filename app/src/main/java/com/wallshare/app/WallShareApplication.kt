package com.wallshare.app

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wallshare.app.wallpaper.WallpaperPollWorker
import java.util.concurrent.TimeUnit

class WallShareApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        schedulePollWorker()
    }

    /**
     * Enqueues a periodic poll that runs every 15 minutes. This is the
     * FALLBACK behind FCM push — if a push is dropped or delayed by an
     * aggressive OEM battery skin, the poll picks it up within 15 minutes.
     *
     * KEEP policy means if a schedule already exists (e.g. from a previous
     * app launch or a BootReceiver trigger), it is left alone — no duplicate
     * scheduling, no timer reset.
     *
     * WorkManager guarantees this survives process kills and reboots.
     */
    private fun schedulePollWorker() {
        val periodicPoll = PeriodicWorkRequestBuilder<WallpaperPollWorker>(
            15, TimeUnit.MINUTES,
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WallpaperPollWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicPoll,
        )

        Log.d("WallShareApp", "Periodic wallpaper poll worker scheduled (15 min)")
    }
}