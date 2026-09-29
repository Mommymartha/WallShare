package com.wallshare.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.wallshare.app.friends.Friend
import com.wallshare.app.ui.theme.AccentPrimary
import com.wallshare.app.ui.theme.AccentSecondary
import com.wallshare.app.ui.theme.DarkBackground
import com.wallshare.app.ui.theme.DarkSurface
import com.wallshare.app.ui.theme.DarkSurfaceVariant
import com.wallshare.app.ui.theme.TextPrimary
import com.wallshare.app.ui.theme.TextSecondary
import com.wallshare.app.wallpaper.WallpaperTarget
import com.wallshare.app.wallpaper.WallpaperViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendWallpaperScreen(
    viewModel: WallpaperViewModel,
    onSent: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> viewModel.onImageSelected(uri) },
    )

    LaunchedEffect(state.sentSuccessfully) {
        if (state.sentSuccessfully) {
            viewModel.consumeSentSuccessfully()
            onSent()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        // Full Bleed Image Background
        if (state.selectedImageUri != null) {
            AsyncImage(
                model = state.selectedImageUri,
                contentDescription = "Selected Wallpaper",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // Dark Gradient Overlay for text readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, DarkBackground.copy(alpha = 0.9f)),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY
                        )
                    )
            )
        } else {
            // Empty State Background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(DarkSurface, DarkBackground)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("No image selected", color = TextSecondary, style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Choose Wallpaper", fontWeight = FontWeight.Bold, color = DarkBackground)
                    }
                }
            }
        }

        // Overlay Controls
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            
            // Re-pick image button (top right)
            if (state.selectedImageUri != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.Top
                ) {
                    OutlinedButton(
                        onClick = { pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TextSecondary.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Change")
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            AnimatedVisibility(visible = state.selectedImageUri != null) {
                Column {
                    // Friend Selection (Horizontal Scroll)
                    Text(
                        text = "Send to…",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 24.dp, bottom = 12.dp)
                    )
                    
                    if (state.friends.isEmpty()) {
                        Text("No friends available", color = TextSecondary, modifier = Modifier.padding(horizontal = 24.dp))
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(state.friends, key = { it.friendshipId }) { friend ->
                                FriendAvatarPicker(
                                    friend = friend,
                                    selected = state.selectedFriend?.friendshipId == friend.friendshipId,
                                    onClick = { viewModel.onFriendSelected(friend) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Target Selection
                    Text(
                        text = "Apply as",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 24.dp, bottom = 12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        WallpaperTarget.entries.forEach { target ->
                            val isSelected = state.target == target
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.onTargetSelected(target) },
                                label = { Text(target.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentSecondary,
                                    selectedLabelColor = TextPrimary,
                                    selectedLeadingIconColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Send Button
                    Button(
                        onClick = { viewModel.onSendClicked(context) },
                        enabled = !state.isSending && state.selectedFriend != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                    ) {
                        if (state.isSending) {
                            CircularProgressIndicator(color = DarkBackground, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = null,
                                tint = DarkBackground,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Send Wallpaper", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = DarkBackground)
                        }
                    }
                }
            }
        }
        
        state.errorMessage?.let { error ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = 96.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                )
            ) {
                Text(
                    text = error,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun FriendAvatarPicker(friend: Friend, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (selected) AccentPrimary else DarkSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = friend.profile.username?.firstOrNull()?.uppercase() ?: "?",
                color = if (selected) DarkBackground else TextSecondary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = friend.profile.username ?: "",
            color = if (selected) AccentPrimary else TextSecondary,
            style = MaterialTheme.typography.labelLarge
        )
    }
}