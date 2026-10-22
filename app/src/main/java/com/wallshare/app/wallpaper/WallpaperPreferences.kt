package com.wallshare.app.wallpaper

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Models the user's auto-apply preferences.
 * Users can independently allow/disallow updates to their home screen and lock screen.
 */
data class WallpaperSettings(
    val autoApplyHome: Boolean = true,
    val autoApplyLock: Boolean = true,
) {
    /** True if at least one screen allows auto-applying wallpapers. */
    val isAnyAutoApplyEnabled: Boolean get() = autoApplyHome || autoApplyLock
}

/**
 * Manages persistence for wallpaper auto-apply preferences using SharedPreferences.
 * Safe to call from both UI (Compose) and background workers (WorkManager).
 */
class WallpaperPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("wallshare_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<WallpaperSettings> = _settings.asStateFlow()

    private fun loadSettings(): WallpaperSettings {
        return WallpaperSettings(
            autoApplyHome = prefs.getBoolean(KEY_AUTO_APPLY_HOME, true),
            autoApplyLock = prefs.getBoolean(KEY_AUTO_APPLY_LOCK, true),
        )
    }

    fun setAutoApplyHome(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_APPLY_HOME, enabled).apply()
        _settings.value = _settings.value.copy(autoApplyHome = enabled)
    }

    fun setAutoApplyLock(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_APPLY_LOCK, enabled).apply()
        _settings.value = _settings.value.copy(autoApplyLock = enabled)
    }

    fun getSettings(): WallpaperSettings = loadSettings()

    companion object {
        private const val KEY_AUTO_APPLY_HOME = "auto_apply_home"
        private const val KEY_AUTO_APPLY_LOCK = "auto_apply_lock"
    }
}
