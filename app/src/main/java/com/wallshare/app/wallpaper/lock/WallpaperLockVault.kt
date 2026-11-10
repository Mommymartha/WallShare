package com.wallshare.app.wallpaper.lock

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
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
private const val KEY_LATEST_HOME_WALLPAPER_PATH = "latest_home_wallpaper_path"
private const val KEY_LATEST_LOCK_WALLPAPER_PATH = "latest_lock_wallpaper_path"
private const val KEY_DEVICE_ADMIN_ENABLED = "device_admin_enabled"

data class LockedWallpapers(
    val homeFile: File?,
    val lockFile: File?,
)

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

    private val _isDeviceAdminEnabledFlow = MutableStateFlow(isDeviceAdminEnabled())
    val isDeviceAdminEnabledFlow: StateFlow<Boolean> = _isDeviceAdminEnabledFlow.asStateFlow()

    fun isLockActive(): Boolean = prefs.getBoolean(KEY_IS_LOCK_ACTIVE, false)

    fun setLockActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_IS_LOCK_ACTIVE, active).apply()
        _isLockActiveFlow.value = active
        Log.d(TAG, "Wallpaper lock active set to: $active")
    }

    fun isDeviceAdminEnabled(): Boolean = prefs.getBoolean(KEY_DEVICE_ADMIN_ENABLED, false)

    fun setDeviceAdminEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEVICE_ADMIN_ENABLED, enabled).apply()
        _isDeviceAdminEnabledFlow.value = enabled
        Log.d(TAG, "Device Admin enabled set to: $enabled")
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

    fun getLatestHomeWallpaperPath(): String? {
        return prefs.getString(KEY_LATEST_HOME_WALLPAPER_PATH, null) ?: prefs.getString(KEY_LATEST_WALLPAPER_PATH, null)
    }

    fun saveLatestHomeWallpaperPath(path: String) {
        prefs.edit().putString(KEY_LATEST_HOME_WALLPAPER_PATH, path).apply()
    }

    fun getLatestLockWallpaperPath(): String? {
        return prefs.getString(KEY_LATEST_LOCK_WALLPAPER_PATH, null)
    }

    fun saveLatestLockWallpaperPath(path: String) {
        prefs.edit().putString(KEY_LATEST_LOCK_WALLPAPER_PATH, path).apply()
    }

    fun getLatestWallpaperPath(): String? {
        return getLatestHomeWallpaperPath()
    }

    fun saveLatestWallpaperPath(path: String) {
        prefs.edit().putString(KEY_LATEST_WALLPAPER_PATH, path).apply()
        saveLatestHomeWallpaperPath(path)
    }

    private fun getLockVaultDir(): File {
        return File(context.filesDir, "lock_vault").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Dedicated file for the locked Home screen wallpaper.
     */
    fun getLockedHomeWallpaperFile(): File {
        return File(getLockVaultDir(), "locked_home_wallpaper.jpg")
    }

    /**
     * Dedicated file for the locked Lock screen wallpaper.
     */
    fun getLockedLockWallpaperFile(): File {
        return File(getLockVaultDir(), "locked_lock_wallpaper.jpg")
    }

    /**
     * Returns the locked wallpaper file for a specific target screen.
     */
    fun getLockedWallpaperFile(which: Int): File {
        return if (which == WallpaperManager.FLAG_LOCK) {
            getLockedLockWallpaperFile()
        } else {
            getLockedHomeWallpaperFile()
        }
    }

    /**
     * Backward-compatible accessor for the primary locked wallpaper file.
     */
    fun getLockedWallpaperFile(): File {
        val legacy = File(getLockVaultDir(), "locked_wallpaper.jpg")
        val home = getLockedHomeWallpaperFile()
        return if (!home.exists() && legacy.exists()) legacy else home
    }

    /**
     * Atomically updates locked wallpaper file(s) for specified screen(s) from a source file.
     * [which] can be [WallpaperManager.FLAG_SYSTEM], [WallpaperManager.FLAG_LOCK], or both.
     */
    fun updateLockedWallpaper(
        sourceFile: File,
        which: Int = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
    ): Boolean {
        return try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) return false
            var success = false

            if ((which and WallpaperManager.FLAG_SYSTEM) != 0) {
                val targetFile = getLockedHomeWallpaperFile()
                copyFileAtomically(sourceFile, targetFile)
                saveLatestHomeWallpaperPath(targetFile.absolutePath)
                prefs.edit().putString(KEY_LATEST_WALLPAPER_PATH, targetFile.absolutePath).apply()
                Log.d(TAG, "Locked HOME wallpaper updated to: ${targetFile.absolutePath}")
                success = true
            }

            if ((which and WallpaperManager.FLAG_LOCK) != 0) {
                val targetFile = getLockedLockWallpaperFile()
                copyFileAtomically(sourceFile, targetFile)
                saveLatestLockWallpaperPath(targetFile.absolutePath)
                Log.d(TAG, "Locked LOCK wallpaper updated to: ${targetFile.absolutePath}")
                success = true
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update locked wallpaper file (which=$which)", e)
            false
        }
    }

    /**
     * Atomically updates locked wallpaper file(s) for specified screen(s) from a Bitmap.
     * [which] can be [WallpaperManager.FLAG_SYSTEM], [WallpaperManager.FLAG_LOCK], or both.
     */
    fun updateLockedWallpaperBitmap(
        bitmap: Bitmap,
        which: Int = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
    ): Boolean {
        return try {
            var success = false

            if ((which and WallpaperManager.FLAG_SYSTEM) != 0) {
                val targetFile = getLockedHomeWallpaperFile()
                writeBitmapAtomically(bitmap, targetFile)
                saveLatestHomeWallpaperPath(targetFile.absolutePath)
                prefs.edit().putString(KEY_LATEST_WALLPAPER_PATH, targetFile.absolutePath).apply()
                Log.d(TAG, "Locked HOME wallpaper updated from bitmap")
                success = true
            }

            if ((which and WallpaperManager.FLAG_LOCK) != 0) {
                val targetFile = getLockedLockWallpaperFile()
                writeBitmapAtomically(bitmap, targetFile)
                saveLatestLockWallpaperPath(targetFile.absolutePath)
                Log.d(TAG, "Locked LOCK wallpaper updated from bitmap")
                success = true
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update locked wallpaper from bitmap (which=$which)", e)
            false
        }
    }

    private fun copyFileAtomically(source: File, target: File) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        source.copyTo(temp, overwrite = true)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun writeBitmapAtomically(bitmap: Bitmap, target: File) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            temp.delete()
        }
    }

    /**
     * Ensures that locked wallpaper files are ready for BOTH Home and Lock screens.
     * Looks up screen-specific locked files, cached history, or captures live system/lock wallpapers.
     */
    fun ensureLockedWallpapersReady(): LockedWallpapers {
        val homeTarget = getLockedHomeWallpaperFile()
        val lockTarget = getLockedLockWallpaperFile()

        var homeReady = homeTarget.exists() && homeTarget.length() > 0L
        var lockReady = lockTarget.exists() && lockTarget.length() > 0L

        // 1. If Home is not ready, check saved Home path or legacy files/cache
        if (!homeReady) {
            val savedHome = getLatestHomeWallpaperPath()
            if (savedHome != null && File(savedHome).let { it.exists() && it.length() > 0L }) {
                copyFileAtomically(File(savedHome), homeTarget)
                homeReady = true
            } else {
                val legacy = File(getLockVaultDir(), "locked_wallpaper.jpg")
                if (legacy.exists() && legacy.length() > 0L) {
                    copyFileAtomically(legacy, homeTarget)
                    homeReady = true
                } else {
                    // Check internal wallpapers cache
                    val wallpapersDir = File(context.filesDir, "wallpapers")
                    val latestCached = wallpapersDir.listFiles()
                        ?.filter { it.isFile && it.length() > 0L }
                        ?.maxByOrNull { it.lastModified() }
                    if (latestCached != null) {
                        copyFileAtomically(latestCached, homeTarget)
                        homeReady = true
                    }
                }
            }
        }

        // 2. If Home is still not ready, capture current system wallpaper
        if (!homeReady) {
            homeReady = captureCurrentWallpaper(WallpaperManager.FLAG_SYSTEM, homeTarget)
        }

        if (homeReady) {
            saveLatestHomeWallpaperPath(homeTarget.absolutePath)
        }

        // 3. If Lock is not ready, check saved Lock path
        if (!lockReady) {
            val savedLock = getLatestLockWallpaperPath()
            if (savedLock != null && File(savedLock).let { it.exists() && it.length() > 0L }) {
                copyFileAtomically(File(savedLock), lockTarget)
                lockReady = true
            }
        }

        // 4. If Lock is still not ready, try capturing live Lock wallpaper
        if (!lockReady) {
            lockReady = captureCurrentWallpaper(WallpaperManager.FLAG_LOCK, lockTarget)
        }

        // 5. If Lock screen has no separate wallpaper, fall back to Home wallpaper
        if (!lockReady && homeReady) {
            copyFileAtomically(homeTarget, lockTarget)
            lockReady = true
        }

        if (lockReady) {
            saveLatestLockWallpaperPath(lockTarget.absolutePath)
        }

        val resolvedHome = if (homeReady && homeTarget.exists() && homeTarget.length() > 0L) homeTarget else null
        val resolvedLock = if (lockReady && lockTarget.exists() && lockTarget.length() > 0L) lockTarget else null

        return LockedWallpapers(resolvedHome, resolvedLock)
    }

    private fun captureCurrentWallpaper(which: Int, targetFile: File): Boolean {
        try {
            val wm = WallpaperManager.getInstance(context)
            // Try getWallpaperFile first
            try {
                val pfd: ParcelFileDescriptor? = wm.getWallpaperFile(which)
                if (pfd != null) {
                    pfd.use { descriptor ->
                        FileInputStream(descriptor.fileDescriptor).use { input ->
                            val temp = File(targetFile.parentFile, "${targetFile.name}.tmp")
                            FileOutputStream(temp).use { output ->
                                input.copyTo(output)
                            }
                            if (targetFile.exists()) targetFile.delete()
                            if (!temp.renameTo(targetFile)) {
                                temp.copyTo(targetFile, overwrite = true)
                                temp.delete()
                            }
                        }
                    }
                    if (targetFile.exists() && targetFile.length() > 0L) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getWallpaperFile($which) unavailable or permission denied", e)
            }

            // Fallback for FLAG_SYSTEM: use wm.drawable
            if (which == WallpaperManager.FLAG_SYSTEM) {
                val drawable = wm.drawable
                if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    writeBitmapAtomically(drawable.bitmap, targetFile)
                    return targetFile.exists() && targetFile.length() > 0L
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not capture wallpaper for which=$which", e)
        }
        return false
    }

    /**
     * Backward-compatible single file readiness check.
     */
    fun ensureLockedWallpaperReady(): File? {
        val result = ensureLockedWallpapersReady()
        return result.homeFile ?: result.lockFile
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
