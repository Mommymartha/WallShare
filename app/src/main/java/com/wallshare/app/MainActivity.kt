package com.wallshare.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallshare.app.auth.AuthState
import com.wallshare.app.auth.AuthViewModel
import com.wallshare.app.friends.FriendsViewModel
import com.wallshare.app.ui.screens.FriendsScreen
import com.wallshare.app.ui.screens.LoginScreen
import com.wallshare.app.ui.screens.SendWallpaperScreen
import com.wallshare.app.ui.screens.UsernameScreen
import com.wallshare.app.ui.theme.AccentPrimary
import com.wallshare.app.ui.theme.DarkBackground
import com.wallshare.app.ui.theme.DarkSurface
import com.wallshare.app.ui.theme.DarkSurfaceVariant
import com.wallshare.app.ui.theme.ErrorRed
import com.wallshare.app.ui.theme.TextPrimary
import com.wallshare.app.ui.theme.TextSecondary
import com.wallshare.app.ui.theme.WallShareTheme
import com.wallshare.app.wallpaper.WallpaperViewModel
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.wallshare.app.wallpaper.WallpaperForegroundService
import com.wallshare.app.wallpaper.WallpaperWorker

/**
 * Thin host for the Compose UI. Routes between auth states only - all actual
 * auth logic lives in AuthRepository/AuthViewModel.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request POST_NOTIFICATIONS permission on Android 13+ (API 33).
        // Without this runtime grant, only foreground service notifications
        // are shown — all other notifications (including our debug/status
        // notifications) are silently dropped by the OS.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        // Prompt the user to exempt WallShare from battery optimization so
        // background FCM pushes can wake the app and apply wallpapers even
        // when the UI isn't open. This only shows the system dialog once —
        // after the user grants it, isIgnoringBatteryOptimizations returns
        // true and this block is skipped on subsequent launches.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }

        // Start the foreground service IMMEDIATELY from onCreate() where
        // we are guaranteed to be in a foreground state. Starting from a
        // LaunchedEffect coroutine can race with Compose lifecycle and
        // miss the foreground window on some OEMs (Motorola, Xiaomi, etc.).
        WallpaperForegroundService.start(this)

        setContent {
            WallShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WallShareRoot()
                }
            }
        }
    }
}

@Composable
private fun WallShareRoot() {
    val context = LocalContext.current

    val container = remember {
        (context.applicationContext as WallShareApplication).container
    }

    val authViewModel: AuthViewModel = viewModel(
        factory = AuthViewModel.factory(container.authRepository),
    )

    val state by authViewModel.state.collectAsStateWithLifecycle()
    val signInError by authViewModel.signInError.collectAsStateWithLifecycle()
    val usernameError by authViewModel.usernameError.collectAsStateWithLifecycle()

    when (val current = state) {

        is AuthState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = AccentPrimary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        is AuthState.SignedOut -> {
            LoginScreen(
                errorMessage = signInError,
                onSignInWithGoogleClick = {
                    authViewModel.onSignInWithGoogleClicked(context)
                },
                onSignInWithUsernameClick = { user, pass ->
                    authViewModel.onSignInWithUsernameClicked(user, pass)
                },
                onSignUpWithUsernameClick = { user, pass ->
                    authViewModel.onSignUpWithUsernameClicked(user, pass)
                }
            )
        }

        is AuthState.NeedsUsername -> {
            UsernameScreen(
                serverError = usernameError,
                onSubmit = { username ->
                    authViewModel.onClaimUsername(username)
                },
            )
        }

        is AuthState.SignedIn -> {

            // Runs once per signed-in user id (not on every recomposition).
            // Covers the case where the FCM token was already minted before
            // login, since onNewToken() only fires for a new token.
            LaunchedEffect(current.profile.id) {
                container.deviceRepository.registerCurrentFcmToken()
                    .onFailure { e ->
                        Log.w(
                            "WallShareRoot",
                            "Device registration failed",
                            e
                        )
                    }

                // Startup/foreground reconciliation check
                try {
                    val pendingRequests = container.wallpaperRepository.getPendingRequests()
                    val workManager = WorkManager.getInstance(context)
                    
                    for (request in pendingRequests) {
                        Log.d("WallShareRoot", "Reconciling missed wallpaper request: ${request.id}")
                        val workRequest = OneTimeWorkRequestBuilder<WallpaperWorker>()
                            .setInputData(workDataOf(WallpaperWorker.KEY_REQUEST_ID to request.id))
                            .setConstraints(
                                Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .build(),
                            )
                            .build()
                
                        workManager.enqueueUniqueWork(
                            "wallpaper-${request.id}",
                            ExistingWorkPolicy.KEEP,
                            workRequest,
                        )
                    }
                } catch (e: Exception) {
                    Log.e("WallShareRoot", "Reconciliation check failed", e)
                }
            }

            SignedInHome(container = container)
        }

        is AuthState.ProfileSyncError -> {
            // Session is valid; only the profile lookup failed.
            // Offer retry rather than forcing the user back through sign-in.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(56.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Something went wrong",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )

                Text(
                    text = "We couldn't load your profile.\nCheck your connection and try again.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.systemBarsPadding()
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        authViewModel.onRetryProfileSync()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                ) {
                    Text("Retry", fontWeight = FontWeight.Bold, color = DarkBackground)
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        authViewModel.onSignOut()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceVariant)
                ) {
                    Text("Sign out")
                }
            }
        }
    }
}


/**
 * The two screens available after the user is signed in.
 */
private enum class HomeTab {
    FRIENDS,
    SEND
}

/**
 * Minimal bottom-nav host for the two SignedIn-level screens.
 *
 * FriendsScreen remains unchanged.
 * SendWallpaperScreen is now reachable through the Send tab.
 */
@Composable
private fun SignedInHome(container: AppContainer) {

    var tab by remember {
        mutableStateOf(HomeTab.FRIENDS)
    }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding()
    ) {

        Box(
            modifier = Modifier.weight(1f)
        ) {
            when (tab) {

                HomeTab.FRIENDS -> {
                    val friendsViewModel: FriendsViewModel = viewModel(
                        factory = FriendsViewModel.factory(
                            container.friendsRepository
                        ),
                    )

                    FriendsScreen(
                        viewModel = friendsViewModel
                    )
                }

                HomeTab.SEND -> {
                    val wallpaperViewModel: WallpaperViewModel = viewModel(
                        factory = WallpaperViewModel.factory(
                            container.friendsRepository,
                            container.wallpaperRepository,
                        ),
                    )

                    SendWallpaperScreen(
                        viewModel = wallpaperViewModel,
                        onSent = {
                            tab = HomeTab.FRIENDS
                        },
                    )
                }
            }
        }

        NavigationBar(
            containerColor = DarkSurface,
            contentColor = TextPrimary,
            tonalElevation = 0.dp
        ) {

            NavigationBarItem(
                selected = tab == HomeTab.FRIENDS,
                onClick = {
                    tab = HomeTab.FRIENDS
                },
                label = {
                    Text("Friends")
                },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.People,
                        contentDescription = "Friends"
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentPrimary,
                    selectedTextColor = AccentPrimary,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor = AccentPrimary.copy(alpha = 0.12f)
                )
            )

            NavigationBarItem(
                selected = tab == HomeTab.SEND,
                onClick = {
                    tab = HomeTab.SEND
                },
                label = {
                    Text("Send")
                },
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Send wallpaper"
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentPrimary,
                    selectedTextColor = AccentPrimary,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor = AccentPrimary.copy(alpha = 0.12f)
                )
            )
        }
    }
}