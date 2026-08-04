package com.ridervoice.navigation
 
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.ui.screens.*
import com.ridervoice.utils.OEMBatteryWarning
 
@Composable
fun NavGraph() {
    val navController = rememberNavController()
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
                val dest = if (FirebaseAuth.getInstance().currentUser != null)
                    Routes.HOME else Routes.LOGIN
                navController.navigate(dest) {
                    popUpTo(Routes.SPLASH) { inclusive = true }
                }
            })
        }
 
        // ── Auth ───────────────────────────────────────────────────────────
        composable(Routes.LOGIN) {
            LoginScreen(
                onGoogleSignInClick = {},
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }
 
        // ── Dashboard ──────────────────────────────────────────────────────
        composable(Routes.HOME) {
            HomeScreen(
                onHostRideClick      = { navController.navigate(Routes.HOST_SETUP) },
                onJoinRideClick      = { navController.navigate(Routes.INVITES_INBOX) },
                onSquadClick         = { navController.navigate(Routes.SQUAD) },
                onSettingsClick      = { navController.navigate(Routes.SETTINGS) },
                onRoutePlannerClick  = { navController.navigate(Routes.ROUTE_PLANNER) },
                onRideHistoryClick   = { navController.navigate(Routes.RIDE_STATS) },
                onDeviceSetupClick   = { navController.navigate(Routes.deviceSetupPath("GLOBAL", isHost = false)) },
                // legacy quick-start kept for testing
                onStartRideClick     = { roomName ->
                    navController.navigate(Routes.deviceSetupPath(roomName, isHost = true))
                }
            )
        }
 
        // ── HOST PATH ──────────────────────────────────────────────────────
 
        // 1. Host names convoy + sets trip details
        composable(Routes.HOST_SETUP) {
            HostSetupScreen(
                onConvoyCreated = { convoyName ->
                    navController.navigate(Routes.inviteFriendsPath(convoyName))
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
            val convoyName = back.arguments?.getString("convoyName") ?: ""
            val isHost     = back.arguments?.getBoolean("isHost") ?: false
            DeviceSetupScreen(
                convoyName  = convoyName,
                isHost      = isHost,
                onReady     = {
                    if (convoyName == "GLOBAL") {
                        navController.popBackStack()
                    } else {
                        val userName = FirebaseAuth.getInstance().currentUser?.displayName ?: "Rider"
                        navController.navigate(Routes.activeRideHudPath(convoyName, userName)) {
                            // Clear the setup stack so back button doesn't go back into setup
                            popUpTo(Routes.HOME) { inclusive = false }
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
            val roomName = back.arguments?.getString("roomName") ?: ""
            val userName = back.arguments?.getString("userName") ?: ""
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
        composable(Routes.SQUAD)        { SquadScreen(onBackClick = { navController.popBackStack() }) }
        composable(Routes.SETTINGS)     { SettingsScreen(onBackClick = { navController.navigateUp() }, onSignOutSuccess = { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }) }
        composable(Routes.ROUTE_PLANNER){ RoutePlannerScreen(onBackClick = { navController.popBackStack() }) }
        composable(Routes.RIDE_STATS)   { RideStatsScreen(onBackClick = { navController.popBackStack() }) }
    }
}
