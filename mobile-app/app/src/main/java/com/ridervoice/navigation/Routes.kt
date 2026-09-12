package com.ridervoice.navigation

object Routes {
    const val SPLASH         = "splash"
    const val LOGIN          = "login"
    const val HOME           = "home"
    const val SQUAD          = "squad"
    const val ROUTE_PLANNER  = "route_planner"
    const val RIDE_STATS     = "ride_stats"
    const val SETTINGS       = "settings"
    const val ACCOUNT        = "account"

    const val HOST_SETUP     = "host_setup"
    const val INVITE_FRIENDS = "invite_friends/{convoyName}"
    const val LOBBY          = "lobby/{convoyName}"

    const val INVITES_INBOX  = "invites_inbox"
    const val REGISTER       = "register"

    const val DEVICE_SETUP   = "device_setup/{convoyName}/{isHost}"
    const val ACTIVE_RIDE_HUD = "active_ride_hud/{roomName}/{userName}"
    const val SOS            = "sos/{roomName}"
    const val POST_RIDE_SUMMARY = "post_ride_summary"
    const val JOIN_ROOM       = "join_room"
    const val JOIN_VIA_TOKEN  = "join_via_token/{token}"
    const val HEADSET_SETTINGS = "headset_settings"
    const val RIDE_REPLAY     = "ride_replay/{rideId}"

    fun inviteFriendsPath(convoyName: String): String {
        val safe = convoyName.ifBlank { "Convoy" }
        return "invite_friends/${android.net.Uri.encode(safe)}"
    }

    fun lobbyPath(convoyName: String): String {
        val safe = convoyName.ifBlank { "Convoy" }
        return "lobby/${android.net.Uri.encode(safe)}"
    }

    fun deviceSetupPath(convoyName: String, isHost: Boolean): String {
        val safe = convoyName.ifBlank { "Convoy" }
        return "device_setup/${android.net.Uri.encode(safe)}/$isHost"
    }

    fun activeRideHudPath(roomName: String, userName: String): String {
        val safeRoom = roomName.ifBlank { "Convoy" }
        val safeUser = userName.ifBlank { "Rider" }
        return "active_ride_hud/${android.net.Uri.encode(safeRoom)}/${android.net.Uri.encode(safeUser)}"
    }

    fun sosPath(roomName: String): String {
        val safe = roomName.ifBlank { "Convoy" }
        return "sos/${android.net.Uri.encode(safe)}"
    }

    fun rideReplayPath(rideId: String): String {
        val safe = rideId.ifBlank { "latest" }
        return "ride_replay/${android.net.Uri.encode(safe)}"
    }

    fun joinViaTokenPath(token: String): String {
        return "join_via_token/${android.net.Uri.encode(token)}"
    }
}
