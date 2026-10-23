package com.ridervoice.ui.components

data class UiMessage(
    val title: String,
    val message: String,
    val actionText: String = "Try again",
    val isFatal: Boolean = false
)

object UiMessageMapper {
    fun map(httpCode: Int, errorCode: String? = null, fallback: String? = null): UiMessage {
        val normalizedCode = errorCode?.uppercase()?.trim()

        return when {
            normalizedCode == "LINK_EXPIRED" -> UiMessage(
                title = "Link expired",
                message = "This invite link has expired. Ask for a new one.",
                actionText = "Go home"
            )
            httpCode == 410 || normalizedCode == "ROOM_ENDED" -> UiMessage(
                title = "Ride ended",
                message = "This ride has ended.",
                actionText = "Go home",
                isFatal = true
            )
            httpCode == 403 && normalizedCode == "REMOVED_BY_HOST" -> UiMessage(
                title = "Removed from ride",
                message = "The host removed you from this ride.",
                actionText = "Go home",
                isFatal = true
            )
            httpCode == 403 && normalizedCode == "NOT_A_MEMBER" -> UiMessage(
                title = "Not a member",
                message = "You're not part of this ride. Ask the host for a code.",
                actionText = "Go home",
                isFatal = true
            )
            httpCode == 404 && (normalizedCode == "CODE_NOT_FOUND" || normalizedCode == "INVALID_CODE") -> UiMessage(
                title = "Invalid code",
                message = "That code isn't right. Check it and try again.",
                actionText = "Try again"
            )
            httpCode == 409 && normalizedCode == "ALREADY_MEMBER" -> UiMessage(
                title = "Already joined",
                message = "You're already in this ride.",
                actionText = "Enter ride"
            )
            httpCode == 403 && normalizedCode == "ONLY_OWNER_CAN_REINVITE" -> UiMessage(
                title = "Host permission needed",
                message = "Only the host can re-invite this rider.",
                actionText = "Back"
            )
            httpCode == 426 || normalizedCode == "UPGRADE_REQUIRED" -> UiMessage(
                title = "Update required",
                message = "Please update RiderVoice to continue.",
                actionText = "Update app",
                isFatal = true
            )
            httpCode == 429 || normalizedCode == "RATE_LIMITED" -> UiMessage(
                title = "Too many attempts",
                message = "Too many tries. Wait a minute.",
                actionText = "Try again"
            )
            httpCode in 500..599 -> UiMessage(
                title = "Connection issue",
                message = "Can't reach RiderVoice. We'll keep trying.",
                actionText = "Retry"
            )
            httpCode == 404 -> UiMessage(
                title = "Not found",
                message = "This ride no longer exists.",
                actionText = "Go home",
                isFatal = true
            )
            !fallback.isNullOrBlank() -> UiMessage(
                title = "Notice",
                message = fallback,
                actionText = "Try again"
            )
            else -> UiMessage(
                title = "Something went wrong",
                message = "Something went wrong. Try again.",
                actionText = "Try again"
            )
        }
    }
}
