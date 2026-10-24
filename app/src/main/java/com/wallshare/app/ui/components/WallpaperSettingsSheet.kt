package com.wallshare.app.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallshare.app.WallShareApplication
import com.wallshare.app.ui.theme.*
import com.wallshare.app.wallpaper.WallpaperPreferences
import com.wallshare.app.wallpaper.lock.WallpaperLockService
import com.wallshare.app.wallpaper.lock.WallpaperLockVault
import com.wallshare.app.wallpaper.lock.ui.PinCreateDialog
import com.wallshare.app.wallpaper.lock.ui.PinVerifyDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperSettingsSheet(
    preferences: WallpaperPreferences,
    lockVault: WallpaperLockVault = (LocalContext.current.applicationContext as WallShareApplication).container.wallpaperLockVault,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val settings by preferences.settings.collectAsStateWithLifecycle()
    val isLockActive by lockVault.isLockActiveFlow.collectAsStateWithLifecycle()
    var showPinCreateDialog by remember { mutableStateOf(false) }
    var showPinVerifyDialog by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = DarkSurfaceVariant)
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AccentPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        tint = AccentPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column {
                    Text(
                        text = "Safety & Wallpaper Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Control how friends can update your device",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Safety Status Card
            val statusColor = when {
                settings.autoApplyHome && settings.autoApplyLock -> AccentPrimary
                settings.isAnyAutoApplyEnabled -> Color(0xFFF59E0B) // Amber
                else -> ErrorRed
            }
            val statusTitle = when {
                settings.autoApplyHome && settings.autoApplyLock -> "Auto-Apply Active"
                settings.autoApplyHome -> "Home Screen Only"
                settings.autoApplyLock -> "Lock Screen Only"
                else -> "Safe Mode: All Updates Paused"
            }
            val statusDesc = when {
                settings.autoApplyHome && settings.autoApplyLock ->
                    "Incoming wallpapers from friends will be applied automatically."
                settings.isAnyAutoApplyEnabled ->
                    "Only the selected screen will be updated. The other screen is protected."
                else ->
                    "No wallpapers will be applied. Your device is completely protected."
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Column {
                        Text(
                            text = statusTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = statusDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Home Screen Switch Row
            SettingToggleItem(
                title = "Home Screen",
                description = "Allow friends to change your home screen wallpaper",
                icon = Icons.Rounded.Wallpaper,
                checked = settings.autoApplyHome,
                onCheckedChange = { preferences.setAutoApplyHome(it) }
            )

            HorizontalDivider(
                color = DarkSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // Lock Screen Switch Row
            SettingToggleItem(
                title = "Lock Screen",
                description = "Allow friends to change your lock screen wallpaper",
                icon = Icons.Rounded.Lock,
                checked = settings.autoApplyLock,
                onCheckedChange = { preferences.setAutoApplyLock(it) }
            )

            HorizontalDivider(
                color = DarkSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // Forced Wallpaper Lock (Anti-Tamper Enforcer)
            SettingToggleItem(
                title = "Forced Wallpaper Lock",
                description = if (isLockActive)
                    "Active: manual wallpaper changes are instantly reverted"
                else
                    "PIN-protected: prevent any manual changes to your wallpaper",
                icon = Icons.Rounded.Shield,
                checked = isLockActive,
                onCheckedChange = { desiredState ->
                    if (desiredState) {
                        // Toggling ON -> Request PIN creation
                        showPinCreateDialog = true
                    } else {
                        // Toggling OFF -> Intercept and verify PIN
                        showPinVerifyDialog = true
                    }
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Notice about old wallpapers cleanup & lock protection
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isLockActive)
                        "🛡️ Wallpaper Lock is ON: A background enforcer service is guarding your wallpaper. To disable or change wallpapers, turn off the toggle and enter your 4-digit PIN."
                    else
                        "🔒 Privacy note: Wallpapers are applied directly via Android's wallpaper service and automatically cleaned up from cloud storage after delivery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isLockActive) AccentPrimary else TextSecondary,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }

    if (showPinCreateDialog) {
        PinCreateDialog(
            onDismiss = { showPinCreateDialog = false },
            onPinCreated = { pin ->
                lockVault.savePin(pin)
                // Securely capture and cache the currently active wallpaper for the enforcer
                lockVault.ensureLockedWallpaperReady()
                lockVault.setLockActive(true)
                WallpaperLockService.start(context)
                showPinCreateDialog = false
            }
        )
    }

    if (showPinVerifyDialog) {
        PinVerifyDialog(
            onDismiss = { showPinVerifyDialog = false },
            onVerifyPin = { pin -> lockVault.verifyPin(pin) },
            onSuccess = {
                lockVault.setLockActive(false)
                lockVault.clearPin()
                WallpaperLockService.stop(context)
                showPinVerifyDialog = false
            }
        )
    }
}

@Composable
private fun SettingToggleItem(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (checked) AccentPrimary.copy(alpha = 0.15f) else DarkSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) AccentPrimary else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DarkBackground,
                checkedTrackColor = AccentPrimary,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = DarkSurfaceVariant,
                uncheckedBorderColor = Color.Transparent,
            )
        )
    }
}
