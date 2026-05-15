package com.ridervoice.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ridervoice.ui.screens.HomeScreen
import com.ridervoice.ui.screens.JoinRoomScreen
import com.ridervoice.ui.screens.RoomScreen

@Composable
fun NavGraph() {

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {

        composable(Routes.HOME) {
            HomeScreen(
                onCreateRoom = {
                    navController.navigate(Routes.ROOM)
                },
                onJoinRoom = {
                    navController.navigate(Routes.JOIN)
                }
            )
        }

        composable(Routes.JOIN) {
            JoinRoomScreen(
                onJoin = {
                    navController.navigate(Routes.ROOM)
                }
            )
        }

        composable(Routes.ROOM) {
            RoomScreen()
        }
    }
}
