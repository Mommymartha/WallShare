package com.wallshare.app

import android.content.Context
import androidx.credentials.CredentialManager
import com.wallshare.app.auth.AuthRepository
import com.wallshare.app.data.remote.SupabaseClientProvider
import com.wallshare.app.friends.FriendsRepository
import com.wallshare.app.notifications.DeviceRepository
import com.wallshare.app.wallpaper.WallpaperRepository

/**
 * Minimal manual DI container. Repositories will be added here in later steps
 * (e.g. `val friendsRepository by lazy { FriendsRepository(supabase) }`).
 *
 * Access from a Composable via:
 *   val container = (LocalContext.current.applicationContext as WallShareApplication).container
 */
class AppContainer(context: Context) {

    val supabase = SupabaseClientProvider.create(context)
    val friendsRepository by lazy { FriendsRepository(supabase) }
    val deviceRepository by lazy { DeviceRepository(supabase) }
    val wallpaperRepository by lazy { WallpaperRepository(supabase) }
    private val credentialManager = CredentialManager.create(context)

    val authRepository by lazy { AuthRepository(supabase, credentialManager) }

    // Repositories are added here in Steps 4-6, e.g.:
    // val friendsRepository by lazy { FriendsRepository(supabase) }
    // val wallpaperRepository by lazy { WallpaperRepository(supabase) }
}
