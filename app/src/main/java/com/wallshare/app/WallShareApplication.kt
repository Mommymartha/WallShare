package com.wallshare.app

import android.app.Application

class WallShareApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        // No manual Firebase setup needed here anymore! The plugin handles it.
        container = AppContainer(this)
    }
}