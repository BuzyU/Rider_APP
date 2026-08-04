package com.ridervoice.navigation
 
object Routes {
    const val SPLASH         = "splash"
    const val LOGIN          = "login"
    const val HOME           = "home"
    const val SQUAD          = "squad"
    const val ROUTE_PLANNER  = "route_planner"
    const val RIDE_STATS     = "ride_stats"
    const val SETTINGS       = "settings"
 
    // Host flow
    const val HOST_SETUP     = "host_setup"
    const val INVITE_FRIENDS = "invite_friends/{convoyName}"
    const val LOBBY          = "lobby/{convoyName}"
 
    // Join flow
    const val INVITES_INBOX  = "invites_inbox"
 
    // Shared
    const val DEVICE_SETUP   = "device_setup/{convoyName}/{isHost}"
    const val ACTIVE_RIDE_HUD = "active_ride_hud/{roomName}/{userName}"
    const val SOS            = "sos/{roomName}"
    const val POST_RIDE_SUMMARY = "post_ride_summary"
 
    fun inviteFriendsPath(convoyName: String) = "invite_friends/$convoyName"
    fun lobbyPath(convoyName: String)         = "lobby/$convoyName"
    fun deviceSetupPath(convoyName: String, isHost: Boolean) = "device_setup/$convoyName/$isHost"
    fun activeRideHudPath(roomName: String, userName: String) = "active_ride_hud/$roomName/$userName"
    fun sosPath(roomName: String) = "sos/$roomName"
}
