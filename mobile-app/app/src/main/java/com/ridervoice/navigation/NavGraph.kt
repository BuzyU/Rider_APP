package com.ridervoice.navigation
 
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.ui.screens.*
import com.ridervoice.utils.OEMBatteryWarning
 
@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    startRoute: String? = null
) {
    val context = LocalContext.current
 
    LaunchedEffect(Unit) {
        if (OEMBatteryWarning.isAggressiveOEM()) {
            android.util.Log.w(
                "NavGraph",
                "⚠️ Aggressive OEM detected: ${OEMBatteryWarning.getBatteryOptimizationWarningText()}"
            )
        }
    }
 
    NavHost(navController = navController, startDestination = Routes.SPLASH) {
 
        // ── Splash ─────────────────────────────────────────────────────────
        composable(Routes.SPLASH) {
            SplashScreen(onSplashFinished = {
                val dest = if (startRoute != null && FirebaseAuth.getInstance().currentUser != null) {
                    startRoute
                } else if (FirebaseAuth.getInstance().currentUser != null) {
                    Routes.HOME
                } else {
                    Routes.LOGIN
                }
                navController.navigate(dest) {
                    popUpTo(Routes.SPLASH) { inclusive = true }
                }
            })
        }
 
        // ── Auth ───────────────────────────────────────────────────────────
        composable(Routes.LOGIN) {
            LoginScreen(
                onGoogleSignInClick = {},
                onPhoneOtpClick = {},
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onRegisterClick = {
                    navController.navigate(Routes.REGISTER)
                }
            )
        }

        // ── Register ────────────────────────────────────────────────────────
        composable(Routes.REGISTER) {
            RegisterScreen(
                onBackClick = { navController.popBackStack() },
                onRegisterSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
 
        // ── Dashboard ──────────────────────────────────────────────────────
        composable(Routes.HOME) {
            val authVm: com.ridervoice.ui.viewmodels.AuthViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            HomeScreen(
                onHostRideClick      = { navController.navigate(Routes.HOST_SETUP) },
                onJoinRideClick      = { navController.navigate(Routes.JOIN_ROOM) },
                onInvitesInboxClick  = { navController.navigate(Routes.INVITES_INBOX) },
                onSosClick           = { navController.navigate(Routes.sosPath("GLOBAL")) },
                onSquadClick         = { navController.navigate(Routes.SQUAD) },
                onSettingsClick      = { navController.navigate(Routes.SETTINGS) },
                onRoutePlannerClick  = { navController.navigate(Routes.ROUTE_PLANNER) },
                onRideHistoryClick   = { navController.navigate(Routes.RIDE_STATS) },
                onDeviceSetupClick   = { navController.navigate(Routes.deviceSetupPath("GLOBAL", isHost = false)) },
                onAccountClick       = { navController.navigate(Routes.ACCOUNT) },
                // legacy quick-start kept for testing
                onStartRideClick     = { roomName ->
                    navController.navigate(Routes.deviceSetupPath(roomName, isHost = true))
                },
                onLogoutSuccess      = {
                    authVm.signOut()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
 
        // ── HOST PATH ──────────────────────────────────────────────────────
 
        // 1. Host names convoy + sets trip details
        composable(Routes.HOST_SETUP) {
            HostSetupScreen(
                onConvoyCreated = { convoyName ->
                    navController.navigate(Routes.lobbyPath(convoyName)) {
                        popUpTo(Routes.HOST_SETUP) { inclusive = true }
                    }
                },
                onBackClick = { navController.popBackStack() }
            )
        }
 
        // 2. Host invites friends by @handle
        composable(
            route = Routes.INVITE_FRIENDS,
            arguments = listOf(navArgument("convoyName") { type = NavType.StringType })
        ) { back ->
            val convoyName = back.arguments?.getString("convoyName") ?: ""
            InviteFriendsScreen(
                convoyName   = convoyName,
                onInvitesSent = { navController.navigate(Routes.lobbyPath(convoyName)) },
                onBackClick  = { navController.popBackStack() }
            )
        }
 
        // 3. Host lobby — waits for riders, sees accept/decline live
        composable(
            route = Routes.LOBBY,
            arguments = listOf(navArgument("convoyName") { type = NavType.StringType })
        ) { back ->
            val convoyName = back.arguments?.getString("convoyName") ?: ""
            LobbyScreen(
                convoyName   = convoyName,
                onStartRide  = {
                    // Host goes through device setup then active HUD
                    navController.navigate(Routes.deviceSetupPath(convoyName, isHost = true))
                },
                onBackClick  = { navController.popBackStack() }
            )
        }
 
        // ── JOIN PATH ──────────────────────────────────────────────────────
 
        // 1. Joiner sees pending invite cards
        composable(Routes.INVITES_INBOX) {
            InvitesInboxScreen(
                onInviteAccepted = { convoyName ->
                    // After accepting → device setup → active HUD
                    navController.navigate(Routes.deviceSetupPath(convoyName, isHost = false))
                },
                onBackClick = { navController.popBackStack() }
            )
        }
 
        // ── DEVICE SETUP (shared by host and joiner) ───────────────────────
        composable(
            route = Routes.DEVICE_SETUP,
            arguments = listOf(
                navArgument("convoyName") { type = NavType.StringType },
                navArgument("isHost") { type = NavType.BoolType }
            )
        ) { back ->
            val rawConvoyName = back.arguments?.getString("convoyName") ?: ""
            val convoyName = try { android.net.Uri.decode(rawConvoyName) } catch (e: Exception) { rawConvoyName }
            val isHost     = back.arguments?.getBoolean("isHost") ?: false
            DeviceSetupScreen(
                convoyName  = convoyName,
                isHost      = isHost,
                onReady     = {
                    if (convoyName == "GLOBAL") {
                        navController.popBackStack()
                    } else {
                        val rawName = FirebaseAuth.getInstance().currentUser?.displayName
                        val userName = if (!rawName.isNullOrBlank()) rawName else "Rider"
                        val safeConvoy = if (convoyName.isNotBlank()) convoyName else "Convoy"
                        navController.navigate(Routes.activeRideHudPath(safeConvoy, userName)) {
                            // Clear the setup stack so back button doesn't go back into setup
                            try {
                                popUpTo(Routes.HOME) { inclusive = false }
                            } catch (e: Exception) {
                                // In case HOME is not in backstack
                            }
                        }
                    }
                },
                onBackClick = { navController.popBackStack() }
            )
        }
 
        // ── ACTIVE RIDE HUD ────────────────────────────────────────────────
        composable(
            route = Routes.ACTIVE_RIDE_HUD,
            arguments = listOf(
                navArgument("roomName") { type = NavType.StringType },
                navArgument("userName") { type = NavType.StringType }
            )
        ) { back ->
            val rawRoomName = back.arguments?.getString("roomName") ?: ""
            val rawUserName = back.arguments?.getString("userName") ?: ""
            val roomName = try { android.net.Uri.decode(rawRoomName) } catch (e: Exception) { rawRoomName }
            val userName = try { android.net.Uri.decode(rawUserName) } catch (e: Exception) { rawUserName }
            RoomScreen(
                roomName = roomName,
                userName = userName,
                onLeave  = {
                    navController.navigate(Routes.POST_RIDE_SUMMARY) {
                        popUpTo(0) { inclusive = false }
                    }
                },
                onSosClick = {
                    navController.navigate(Routes.sosPath(roomName))
                }
            )
        }
 
        // ── SOS SCREEN ─────────────────────────────────────────────────────
        composable(
            route = Routes.SOS,
            arguments = listOf(navArgument("roomName") { type = NavType.StringType })
        ) { back ->
            val roomName = back.arguments?.getString("roomName") ?: ""
            val vm: com.ridervoice.ui.viewmodels.SosViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val state by vm.state.collectAsState()
            
            SosScreen(
                state = state,
                onCancelClick = { navController.popBackStack() },
                onSend = { vm.sendAlert(roomName) }
            )
            
            LaunchedEffect(state) {
                if (state is com.ridervoice.ui.viewmodels.SosState.Sent) {
                    navController.popBackStack()
                }
            }
        }
 
        // ── POST RIDE SUMMARY ─────────────────────────────────────────────
        composable(Routes.POST_RIDE_SUMMARY) {
            val vm: com.ridervoice.ui.viewmodels.PostRideSummaryViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val summary by vm.summary.collectAsState()
 
            val current = summary
            if (current == null) {
                // No pending ride (e.g. process was killed) — nothing to show, bounce home.
                LaunchedEffect(Unit) {
                    navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                }
            } else {
                PostRideSummaryScreen(
                    durationMinutes = current.durationMinutes,
                    distanceKm      = current.distanceKm,
                    topSpeed        = current.topSpeedKmh,
                    onSaveToLogbook = {
                        vm.save()
                        navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                    },
                    onDiscard = {
                        vm.discard()
                        navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                    }
                )
            }
        }
 
        // ── Other screens ──────────────────────────────────────────────────
        composable(Routes.SQUAD) {
            SquadScreen(
                onBackClick = { navController.popBackStack() },
                onJoinRoom = { roomName ->
                    navController.navigate(Routes.deviceSetupPath(roomName, isHost = false))
                }
            )
        }
        composable(Routes.ACCOUNT) {
            AccountScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onCheckForUpdates = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBackClick = { navController.navigateUp() },
                onNavigateToHeadsetSettings = { navController.navigate(Routes.HEADSET_SETTINGS) },
                onNavigateToAccount = { navController.navigate(Routes.ACCOUNT) },
                onSignOutSuccess = {
                    navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                }
            )
        }
        composable(Routes.ROUTE_PLANNER){ RoutePlannerScreen(onBackClick = { navController.popBackStack() }) }
        composable(Routes.RIDE_STATS) {
            RideStatsScreen(
                onBackClick = { navController.popBackStack() },
                onReplayRide = { rideId ->
                    navController.navigate(Routes.rideReplayPath(rideId))
                }
            )
        }
        composable(Routes.JOIN_ROOM) {
            JoinRoomScreen(
                onJoin = { roomCode, userName ->
                    navController.navigate(Routes.deviceSetupPath(roomCode, isHost = false))
                },
                onBackClick = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.JOIN_VIA_TOKEN,
            arguments = listOf(navArgument("token") { type = NavType.StringType })
        ) { backStackEntry ->
            val token = backStackEntry.arguments?.getString("token") ?: ""
            JoinRoomScreen(
                initialToken = token,
                onJoin = { roomCode, userName ->
                    navController.navigate(Routes.deviceSetupPath(roomCode, isHost = false))
                },
                onBackClick = { navController.popBackStack() }
            )
        }
        composable(Routes.HEADSET_SETTINGS) {
            HeadsetSettingsScreen(onBackClick = { navController.popBackStack() })
        }
        composable(
            route = Routes.RIDE_REPLAY,
            arguments = listOf(navArgument("rideId") { type = NavType.StringType; defaultValue = "latest" })
        ) { backStackEntry ->
            val rideId = backStackEntry.arguments?.getString("rideId") ?: "latest"
            RideReplayScreen(
                rideId = rideId,
                waypoints = emptyList(),
                events = emptyList(),
                onBack = { navController.popBackStack() }
            )
        }
    }
}
