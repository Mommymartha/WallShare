package com.wallshare.app.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallshare.app.data.model.Profile
import com.wallshare.app.friends.FriendsUiState
import com.wallshare.app.friends.FriendsViewModel
import com.wallshare.app.ui.components.FriendListItem
import com.wallshare.app.ui.components.IncomingRequestItem
import com.wallshare.app.ui.components.OutgoingRequestItem
import com.wallshare.app.ui.components.UserSearchResultItem
import com.wallshare.app.ui.theme.AccentPrimary
import com.wallshare.app.ui.theme.DarkBackground
import com.wallshare.app.ui.theme.DarkSurface
import com.wallshare.app.ui.theme.DarkSurfaceVariant
import com.wallshare.app.ui.theme.TextPrimary
import com.wallshare.app.ui.theme.TextSecondary

private enum class FriendsTab(val label: String, val icon: ImageVector) {
    FRIENDS("Friends", Icons.Rounded.People),
    REQUESTS("Requests", Icons.Rounded.MailOutline),
    FIND("Find", Icons.Rounded.PersonSearch)
}

@Composable
fun FriendsScreen(viewModel: FriendsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(FriendsTab.FRIENDS) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .systemBarsPadding()
    ) {
        // Custom Segmented Control Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(DarkSurface),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FriendsTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (isSelected) AccentPrimary else Color.Transparent)
                        .clickable { selectedTab = tab },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            tint = if (isSelected) DarkBackground else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) DarkBackground else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        val statusMessage = state.actionMessage ?: state.actionError
        if (statusMessage != null) {
            Text(
                text = statusMessage,
                color = if (state.actionError != null) MaterialTheme.colorScheme.error else AccentPrimary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }

        // Animated Tab Content
        Crossfade(
            targetState = selectedTab,
            animationSpec = tween(300),
            label = "TabContentCrossfade",
            modifier = Modifier.weight(1f)
        ) { tab ->
            when (tab) {
                FriendsTab.FRIENDS -> FriendsListTab(state)
                FriendsTab.REQUESTS -> RequestsTab(
                    state = state,
                    onAccept = viewModel::onAcceptRequest,
                    onReject = viewModel::onRejectRequest,
                )
                FriendsTab.FIND -> FindFriendsTab(
                    query = state.searchQuery,
                    results = state.searchResults,
                    isSearching = state.isSearching,
                    onQueryChange = viewModel::onSearchQueryChanged,
                    onSendRequest = viewModel::onSendFriendRequest,
                )
            }
        }
    }
}

@Composable
private fun FriendsListTab(state: FriendsUiState) {
    when {
        state.isLoadingFriends -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentPrimary, strokeWidth = 3.dp)
            }
        }
        state.friends.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.GroupAdd,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No friends yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = "Try the Find tab to search for people!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary.copy(alpha = 0.7f)
                    )
                }
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(state.friends, key = { it.friendshipId }) { friend ->
                    FriendListItem(friend = friend)
                }
            }
        }
    }
}

@Composable
private fun RequestsTab(
    state: FriendsUiState,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (state.incomingRequests.isNotEmpty()) {
            item {
                Text(
                    text = "Incoming Requests",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 12.dp)
                )
            }
            items(state.incomingRequests, key = { it.requestId }) { request ->
                IncomingRequestItem(
                    request = request,
                    onAccept = { onAccept(request.requestId) },
                    onReject = { onReject(request.requestId) },
                )
            }
        }

        if (state.outgoingRequests.isNotEmpty()) {
            item {
                Text(
                    text = "Pending Outgoing",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp)
                )
            }
            items(state.outgoingRequests, key = { it.requestId }) { request ->
                OutgoingRequestItem(request = request)
            }
        }
        
        if (state.incomingRequests.isEmpty() && state.outgoingRequests.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Inbox,
                            contentDescription = null,
                            tint = TextSecondary.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No pending requests",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindFriendsTab(
    query: String,
    results: List<Profile>,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSendRequest: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Search by username", color = TextSecondary) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = TextSecondary
                )
            },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentPrimary,
                unfocusedBorderColor = DarkSurfaceVariant,
                cursorColor = AccentPrimary,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isSearching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = AccentPrimary,
                trackColor = DarkSurface
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
        ) {
            items(results, key = { it.id }) { profile ->
                UserSearchResultItem(
                    profile = profile,
                    onSendRequest = { profile.username?.let(onSendRequest) },
                )
            }
        }
    }
}