package com.ridervoice.navigation

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val HOME = "home"
    const val SQUAD = "squad"
    const val JOIN = "join"
    const val ROUTE_PLANNER = "route_planner"
    const val SOS_EMERGENCY = "sos_emergency"
    const val RIDE_STATS = "ride_stats"
    const val SETTINGS = "settings"
    
    // Template with named args
    const val ACTIVE_RIDE_HUD = "active_ride_hud/{roomName}/{userName}"
    const val VOICE_CHANNEL = "voice_channel/{roomName}/{userName}"

    fun activeRideHudPath(roomName: String, userName: String) = "active_ride_hud/$roomName/$userName"
    fun voiceChannelPath(roomName: String, userName: String) = "voice_channel/$roomName/$userName"
}
