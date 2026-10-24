package com.wallshare.app.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.wallshare.app.ui.theme.*
import com.wallshare.app.wallpaper.WallpaperHistoryItem
import com.wallshare.app.wallpaper.WallpaperHistoryViewModel
import com.wallshare.app.wallpaper.WallpaperTarget
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperHistoryScreen(
    viewModel: WallpaperHistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedApplyItem by remember { mutableStateOf<WallpaperHistoryItem?>(null) }

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissUserMessage()
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissErrorMessage()
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    containerColor = DarkSurfaceVariant,
                    contentColor = TextPrimary,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(data.visuals.message, fontWeight = FontWeight.Medium)
                }
            }
        },
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Wallpaper History",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Your latest 16 received wallpapers",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }

                IconButton(
                    onClick = { viewModel.refresh() },
                    modifier = Modifier
                        .size(40.dp)
                        .background(DarkSurfaceVariant, CircleShape)
                ) {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(
                            color = AccentPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
                            tint = AccentPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                state.isLoading && state.items.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = AccentPrimary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Loading history...",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                state.items.isEmpty() -> {
                    EmptyHistoryView(onRefresh = { viewModel.refresh() })
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = state.items,
                            key = { it.id }
                        ) { item ->
                            WallpaperHistoryCard(
                                item = item,
                                isDownloading = state.downloadingIds.contains(item.id),
                                isSaved = state.savedIds.contains(item.id),
                                isApplying = state.applyingIds.contains(item.id),
                                onPreview = { viewModel.onPreviewItem(item) },
                                onDownload = { viewModel.downloadWallpaper(item) },
                                onApplyClick = { selectedApplyItem = item }
                            )
                        }
                    }
                }
            }
        }
    }

    // Apply Target Selector Bottom Sheet / Dialog
    selectedApplyItem?.let { item ->
        ApplyTargetDialog(
            item = item,
            onDismiss = { selectedApplyItem = null },
            onApply = { target ->
                viewModel.applyWallpaper(item, target)
                selectedApplyItem = null
            }
        )
    }

    // Full-Screen Preview Dialog
    state.previewItem?.let { item ->
        WallpaperPreviewDialog(
            item = item,
            isDownloading = state.downloadingIds.contains(item.id),
            isSaved = state.savedIds.contains(item.id),
            onDismiss = { viewModel.onPreviewItem(null) },
            onDownload = { viewModel.downloadWallpaper(item) },
            onApply = { target ->
                viewModel.applyWallpaper(item, target)
            }
        )
    }
}

@Composable
private fun WallpaperHistoryCard(
    item: WallpaperHistoryItem,
    isDownloading: Boolean,
    isSaved: Boolean,
    isApplying: Boolean,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onApplyClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .border(1.dp, AccentPrimary.copy(alpha = 0.15f), RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onPreview),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Wallpaper Image
            val imageModel = item.localFilePath?.let { File(it) }
            if (imageModel != null && imageModel.exists()) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = "Wallpaper from @${item.senderUsername}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(DarkSurfaceVariant, DarkSurface)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = AccentPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Dark gradient overlay for bottom details & buttons
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.35f),
                            0.4f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                        )
                    )
            )

            // Top Badges: Target tag & relative time
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Target Pill
                val targetLabel = when (item.target) {
                    "HOME" -> "Home"
                    "LOCK" -> "Lock"
                    else -> "Both"
                }
                Box(
                    modifier = Modifier
                        .background(
                            Color.Black.copy(alpha = 0.65f),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            0.5.dp,
                            AccentPrimary.copy(alpha = 0.4f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = targetLabel,
                        color = AccentPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // Time Badge
                Text(
                    text = formatRelativeTime(item.createdAt),
                    color = TextPrimary.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Bottom Content: Sender info and action buttons
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                // Sender handle & name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                Brush.linearGradient(listOf(AccentPrimary, AccentSecondary)),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (item.senderUsername?.firstOrNull() ?: 'U').uppercase(),
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = item.senderUsername?.let { "@$it" } ?: "Friend",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons: Download + Apply
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Download to Gallery button
                    Button(
                        onClick = onDownload,
                        enabled = !isDownloading,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSaved) SuccessGreen.copy(alpha = 0.25f) else DarkSurfaceVariant,
                            contentColor = if (isSaved) SuccessGreen else TextPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f).height(34.dp),
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                color = AccentPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                        } else if (isSaved) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Saved",
                                modifier = Modifier.size(15.dp),
                                tint = SuccessGreen
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Saved", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "Download",
                                modifier = Modifier.size(15.dp),
                                tint = AccentPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Set Wallpaper button
                    IconButton(
                        onClick = onApplyClick,
                        enabled = !isApplying,
                        modifier = Modifier
                            .size(34.dp)
                            .background(AccentPrimary.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    ) {
                        if (isApplying) {
                            CircularProgressIndicator(
                                color = AccentPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Wallpaper,
                                contentDescription = "Apply as wallpaper",
                                tint = AccentPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryView(onRefresh: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface, RoundedCornerShape(24.dp))
                .border(1.dp, AccentPrimary.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                .padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(AccentPrimary.copy(alpha = 0.25f), Color.Transparent)
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoLibrary,
                    contentDescription = null,
                    tint = AccentPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No Wallpapers Yet",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "When your friends send you wallpapers, your last 16 will appear here ready to view, re-apply, and download!",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedButton(
                onClick = onRefresh,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPrimary),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(AccentPrimary, AccentSecondary))
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check for Updates")
            }
        }
    }
}

@Composable
private fun ApplyTargetDialog(
    item: WallpaperHistoryItem,
    onDismiss: () -> Unit,
    onApply: (WallpaperTarget) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = "Set as Wallpaper",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Choose which screen to apply this wallpaper from @${item.senderUsername ?: "friend"}:",
                    color = TextSecondary,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = { onApply(WallpaperTarget.HOME) },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Home, contentDescription = null, tint = AccentPrimary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Home Screen", color = TextPrimary)
                }

                Button(
                    onClick = { onApply(WallpaperTarget.LOCK) },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = AccentSecondary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Lock Screen", color = TextPrimary)
                }

                Button(
                    onClick = { onApply(WallpaperTarget.BOTH) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Smartphone, contentDescription = null, tint = AccentPrimary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Both Home & Lock", color = AccentPrimary, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun WallpaperPreviewDialog(
    item: WallpaperHistoryItem,
    isDownloading: Boolean,
    isSaved: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onApply: (WallpaperTarget) -> Unit,
) {
    var showTargetPicker by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Full Screen Image
            val imageModel = item.localFilePath?.let { File(it) }
            if (imageModel != null && imageModel.exists()) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = "Full Wallpaper",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }

            // Top Bar with Close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = item.senderUsername?.let { "@$it" } ?: "Wallpaper",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = formatRelativeTime(item.createdAt),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = TextPrimary
                    )
                }
            }

            // Bottom Actions Bar (elevated well above navigation bar and bottom screen edge)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.75f),
                                Color.Black.copy(alpha = 0.95f),
                            )
                        )
                    )
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 56.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Download to Gallery button
                    Button(
                        onClick = onDownload,
                        enabled = !isDownloading,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSaved) SuccessGreen.copy(alpha = 0.25f) else DarkSurfaceVariant,
                            contentColor = if (isSaved) SuccessGreen else TextPrimary,
                        ),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                color = AccentPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else if (isSaved) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = SuccessGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Saved to Gallery", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Rounded.Download, contentDescription = null, tint = AccentPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Download", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Set Wallpaper button
                    Button(
                        onClick = { showTargetPicker = true },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Rounded.Wallpaper, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Apply", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showTargetPicker) {
        ApplyTargetDialog(
            item = item,
            onDismiss = { showTargetPicker = false },
            onApply = { target ->
                onApply(target)
                showTargetPicker = false
            }
        )
    }
}

/** Formats ISO-8601 created_at timestamp into user-friendly relative time */
private fun formatRelativeTime(isoString: String): String {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val instant = java.time.OffsetDateTime.parse(isoString).toInstant()
            val diffMs = System.currentTimeMillis() - instant.toEpochMilli()
            val diffSec = diffMs / 1000
            val diffMin = diffSec / 60
            val diffHours = diffMin / 60
            val diffDays = diffHours / 24

            when {
                diffMin < 1 -> "Just now"
                diffMin < 60 -> "${diffMin}m ago"
                diffHours < 24 -> "${diffHours}h ago"
                diffDays == 1L -> "Yesterday"
                diffDays < 7 -> "${diffDays}d ago"
                else -> {
                    val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d")
                        .withZone(java.time.ZoneId.systemDefault())
                    formatter.format(instant)
                }
            }
        } else {
            isoString.take(10)
        }
    } catch (e: Exception) {
        isoString.take(10)
    }
}
