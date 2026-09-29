package com.wallshare.app.data.remote

import android.content.Context
import com.wallshare.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

/**
 * Builds the single [SupabaseClient] used by the whole app.
 *
 * IMPORTANT: `supabaseKey` here is the PUBLIC anon key. It is safe to ship in the APK
 * because every table is protected by Row Level Security policies (Step 2) - the anon
 * key alone grants no access to anything. The service_role key must NEVER appear here;
 * it only ever lives in Supabase Edge Function secrets.
 *
 * The URL/key are read from BuildConfig, which is populated from local.properties /
 * CI secrets - never hardcoded, never committed.
 */
object SupabaseClientProvider {

    fun create(context: Context): SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
    ) {
        install(Auth)
        install(Postgrest)
        install(Storage)
        install(Realtime) // used later for live online/offline + friend-request updates
    }
}
