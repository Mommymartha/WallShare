package com.wallshare.app.wallpaper.lock

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

private const val TAG = "WallpaperLockVault"
private const val PREFS_FILE = "wallshare_lock_vault"
private const val PREFS_FALLBACK_FILE = "wallshare_lock_vault_fallback"

private const val KEY_IS_LOCK_ACTIVE = "is_lock_active"
private const val KEY_PIN_HASH = "pin_hash"
private const val KEY_PIN_SALT = "pin_salt"
private const val KEY_LATEST_WALLPAPER_PATH = "latest_wallpaper_path"

/**
 * Manages secure persistent storage for the Forced Wallpaper Lock feature.
 *
 * Utilizes [EncryptedSharedPreferences] backed by Android Keystore (AES256-GCM / AES256-SIV)
 * to securely store:
 * 1. Lock activation status ([isLockActive])
 * 2. Cryptographically salted SHA-256 hash of the 4-digit PIN ([verifyPin])
 * 3. Path of the active wallpaper file to enforce ([getLatestWallpaperPath])
 *
 * If KeyStore encounters hardware or OEM incompatibility, it seamlessly falls back to
 * private SharedPreferences while retaining the salted SHA-256 PIN hashing security guarantee.
 */
class WallpaperLockVault(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences initialization failed, falling back to private prefs", e)
            context.getSharedPreferences(PREFS_FALLBACK_FILE, Context.MODE_PRIVATE)
        }
    }

    private val _isLockActiveFlow = MutableStateFlow(isLockActive())
    val isLockActiveFlow: StateFlow<Boolean> = _isLockActiveFlow.asStateFlow()

    fun isLockActive(): Boolean = prefs.getBoolean(KEY_IS_LOCK_ACTIVE, false)

    fun setLockActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_IS_LOCK_ACTIVE, active).apply()
        _isLockActiveFlow.value = active
        Log.d(TAG, "Wallpaper lock active set to: $active")
    }

    /**
     * Hashes the 4-digit PIN with a newly generated random 16-byte salt using SHA-256
     * and securely persists both the salt and the digest.
     */
    fun savePin(pin: String) {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be exactly 4 digits" }
        val salt = generateSalt()
        val hash = hashPin(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, hash)
            .apply()
        Log.d(TAG, "New 4-digit PIN securely hashed and stored")
    }

    /**
     * Verifies the entered PIN against the stored salted hash using constant-time equality check.
     */
    fun verifyPin(enteredPin: String): Boolean {
        if (enteredPin.length != 4 || !enteredPin.all { it.isDigit() }) return false
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val storedSalt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val computedHash = hashPin(enteredPin, storedSalt)

        return MessageDigest.isEqual(
            computedHash.toByteArray(Charsets.UTF_8),
            storedHash.toByteArray(Charsets.UTF_8)
        )
    }

    fun hasPin(): Boolean {
        return prefs.getString(KEY_PIN_HASH, null) != null
    }

    fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_HASH)
            .apply()
        Log.d(TAG, "PIN cleared from security vault")
    }

    fun getLatestWallpaperPath(): String? {
        return prefs.getString(KEY_LATEST_WALLPAPER_PATH, null)
    }

    fun saveLatestWallpaperPath(path: String) {
        prefs.edit().putString(KEY_LATEST_WALLPAPER_PATH, path).apply()
    }

    /**
     * Directory dedicated to the locked wallpaper file so it survives general history prunes.
     */
    fun getLockedWallpaperFile(): File {
        val dir = File(context.filesDir, "lock_vault").apply {
            if (!exists()) mkdirs()
        }
        return File(dir, "locked_wallpaper.jpg")
    }

    /**
     * Ensures that a locked wallpaper image file is ready for the enforcer service to reapply.
     * Looks up existing locked file, cached wallpapers, or captures the current system wallpaper.
     */
    fun ensureLockedWallpaperReady(): File? {
        val vaultFile = getLockedWallpaperFile()
        if (vaultFile.exists() && vaultFile.length() > 0) {
            saveLatestWallpaperPath(vaultFile.absolutePath)
            return vaultFile
        }

        // 1. Check if the latest saved wallpaper path exists
        val savedPath = getLatestWallpaperPath()
        if (savedPath != null) {
            val sourceFile = File(savedPath)
            if (sourceFile.exists() && sourceFile.length() > 0) {
                sourceFile.copyTo(vaultFile, overwrite = true)
                saveLatestWallpaperPath(vaultFile.absolutePath)
                return vaultFile
            }
        }

        // 2. Check if any file exists in app's internal wallpapers directory
        val wallpapersDir = File(context.filesDir, "wallpapers")
        if (wallpapersDir.exists()) {
            val latestCached = wallpapersDir.listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.maxByOrNull { it.lastModified() }
            if (latestCached != null) {
                latestCached.copyTo(vaultFile, overwrite = true)
                saveLatestWallpaperPath(vaultFile.absolutePath)
                return vaultFile
            }
        }

        // 3. Fallback: capture current system wallpaper
        try {
            val wm = WallpaperManager.getInstance(context)
            val drawable = wm.drawable
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                FileOutputStream(vaultFile).use { out ->
                    drawable.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                saveLatestWallpaperPath(vaultFile.absolutePath)
                return vaultFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not capture system wallpaper for vault", e)
        }

        return if (vaultFile.exists() && vaultFile.length() > 0) vaultFile else null
    }

    fun getLastAppliedSystemId(): Int = prefs.getInt("last_applied_system_id", -1)
    fun saveLastAppliedSystemId(id: Int) { prefs.edit().putInt("last_applied_system_id", id).apply() }

    fun getLastAppliedLockId(): Int = prefs.getInt("last_applied_lock_id", -1)
    fun saveLastAppliedLockId(id: Int) { prefs.edit().putInt("last_applied_lock_id", id).apply() }

    private fun generateSalt(): String {
        val random = SecureRandom()
        val saltBytes = ByteArray(16)
        random.nextBytes(saltBytes)
        return Base64.encodeToString(saltBytes, Base64.NO_WRAP)
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = "$salt:$pin".toByteArray(Charsets.UTF_8)
        val hash = md.digest(input)
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
}
