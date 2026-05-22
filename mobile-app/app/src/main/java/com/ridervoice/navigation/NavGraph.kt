package com.ridervoice.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ridervoice.ui.screens.*
import com.google.firebase.auth.FirebaseAuth

@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onSplashFinished = {
                    val startDest = if (FirebaseAuth.getInstance().currentUser != null) Routes.HOME else Routes.LOGIN
                    navController.navigate(startDest) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(
                onGoogleSignInClick = { 
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onPhoneOtpClick = { },
                onGuestClick = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onStartRideClick = {
                    val roomName = "PUNE NIGHT RIDERS"
                    val userName = FirebaseAuth.getInstance().currentUser?.displayName ?: "Rider"
                    navController.navigate(Routes.activeRideHudPath(roomName, userName))
                },
                onSquadClick = { navController.navigate(Routes.SQUAD) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onRoutePlannerClick = { navController.navigate(Routes.ROUTE_PLANNER) },
                onRideHistoryClick = { navController.navigate(Routes.RIDE_STATS) }
            )
        }

        composable(Routes.SQUAD) {
            SquadScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Routes.ROUTE_PLANNER) {
            RoutePlannerScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Routes.RIDE_STATS) {
            RideStatsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        // Room -> Active Ride HUD
        composable(
            route = Routes.ACTIVE_RIDE_HUD,
            arguments = listOf(
                navArgument("roomName") { type = NavType.StringType },
                navArgument("userName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val roomName = backStackEntry.arguments?.getString("roomName") ?: ""
            val userName = backStackEntry.arguments?.getString("userName") ?: ""
            // Currently RoomScreen handles this
            RoomScreen(
                roomName = roomName,
                userName = userName,
                onLeave = { navController.popBackStack(Routes.HOME, false) }
            )
        }
    }
}
