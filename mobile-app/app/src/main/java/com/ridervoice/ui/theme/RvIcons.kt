package com.ridervoice.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsBluetooth
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Mirrors directional icons horizontally when layout direction is RTL.
 * Replaces Icons.AutoMirrored (unavailable in material-icons-extended:1.3.1).
 */
@Composable
fun Modifier.rvRtlMirrored(): Modifier =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        this.scale(scaleX = -1f, scaleY = 1f)
    } else {
        this
    }

/**
 * Centralized semantic icon catalog for RiderVoice presentation UI.
 * All non-frozen Compose screens and components reference [RvIcons] instead of raw [Icons].
 */
object RvIcons {
    /** Toggleable glyph policy for leaving a voice ride (CallEnd by default, ExitToApp optional). */
    const val USE_EXIT_GLYPH_FOR_LEAVE = false

    // Bottom navigation Outlined / Filled pairs
    val NavHome: ImageVector get() = Icons.Outlined.GridView
    val NavHomeSelected: ImageVector get() = Icons.Filled.GridView
    val NavHistory: ImageVector get() = Icons.Outlined.History
    val NavHistorySelected: ImageVector get() = Icons.Filled.History
    val NavSquad: ImageVector get() = Icons.Outlined.Group
    val NavSquadSelected: ImageVector get() = Icons.Filled.Group
    val NavSettings: ImageVector get() = Icons.Outlined.Settings
    val NavSettingsSelected: ImageVector get() = Icons.Filled.Settings

    // Ride & Voice
    val Leave: ImageVector
        get() = if (USE_EXIT_GLYPH_FOR_LEAVE) Icons.Filled.ExitToApp else Icons.Filled.CallEnd
    val CallEnd: ImageVector get() = Icons.Filled.CallEnd
    val Mic: ImageVector get() = Icons.Filled.Mic
    val MicOff: ImageVector get() = Icons.Filled.MicOff
    val VolumeUp: ImageVector get() = Icons.Filled.VolumeUp
    val VolumeOff: ImageVector get() = Icons.Filled.VolumeOff
    val Headset: ImageVector get() = Icons.Filled.Headset
    val HeadsetMic: ImageVector get() = Icons.Filled.HeadsetMic
    val Hearing: ImageVector get() = Icons.Filled.Hearing
    val RecordVoiceOver: ImageVector get() = Icons.Filled.RecordVoiceOver
    val GraphicEq: ImageVector get() = Icons.Filled.GraphicEq
    val Podcasts: ImageVector get() = Icons.Filled.Podcasts
    val Phone: ImageVector get() = Icons.Filled.Phone
    val PhoneInTalk: ImageVector get() = Icons.Filled.PhoneInTalk

    // Connectivity & Bluetooth
    val Bluetooth: ImageVector get() = Icons.Filled.Bluetooth
    val BluetoothConnected: ImageVector get() = Icons.Filled.BluetoothConnected
    val BluetoothDisabled: ImageVector get() = Icons.Filled.BluetoothDisabled
    val BluetoothSearching: ImageVector get() = Icons.Filled.BluetoothSearching
    val SettingsBluetooth: ImageVector get() = Icons.Filled.SettingsBluetooth
    val WifiOff: ImageVector get() = Icons.Filled.WifiOff
    val WifiTethering: ImageVector get() = Icons.Filled.WifiTethering
    val NetworkCheck: ImageVector get() = Icons.Filled.NetworkCheck

    // Safety & Alerts
    val Warning: ImageVector get() = Icons.Filled.Warning
    val ErrorOutline: ImageVector get() = Icons.Filled.ErrorOutline
    val Info: ImageVector get() = Icons.Filled.Info
    val Security: ImageVector get() = Icons.Filled.Security
    val VerifiedUser: ImageVector get() = Icons.Filled.VerifiedUser
    val Verified: ImageVector get() = Icons.Filled.Verified
    val VpnKey: ImageVector get() = Icons.Filled.VpnKey
    val LocalHospital: ImageVector get() = Icons.Filled.LocalHospital
    val BatteryAlert: ImageVector get() = Icons.Filled.BatteryAlert
    val Vibration: ImageVector get() = Icons.Filled.Vibration

    // Navigation & Directional
    val Back: ImageVector get() = Icons.Filled.ArrowBack
    val BackIos: ImageVector get() = Icons.Filled.ArrowBackIosNew
    val Forward: ImageVector get() = Icons.Filled.ArrowForward
    val ChevronRight: ImageVector get() = Icons.Filled.ChevronRight
    val ExpandLess: ImageVector get() = Icons.Filled.ExpandLess
    val ExpandMore: ImageVector get() = Icons.Filled.ExpandMore
    val MoreVert: ImageVector get() = Icons.Filled.MoreVert
    val SwapVert: ImageVector get() = Icons.Filled.SwapVert
    val GridView: ImageVector get() = Icons.Filled.GridView

    // Route, Telemetry & Playback
    val TwoWheeler: ImageVector get() = Icons.Filled.TwoWheeler
    val Navigation: ImageVector get() = Icons.Filled.Navigation
    val MyLocation: ImageVector get() = Icons.Filled.MyLocation
    val LocationOn: ImageVector get() = Icons.Filled.LocationOn
    val Map: ImageVector get() = Icons.Filled.Map
    val Route: ImageVector get() = Icons.Filled.Route
    val Flag: ImageVector get() = Icons.Filled.Flag
    val Layers: ImageVector get() = Icons.Filled.Layers
    val Straighten: ImageVector get() = Icons.Filled.Straighten
    val Speed: ImageVector get() = Icons.Filled.Speed
    val Timer: ImageVector get() = Icons.Filled.Timer
    val Schedule: ImageVector get() = Icons.Filled.Schedule
    val History: ImageVector get() = Icons.Filled.History
    val PlayArrow: ImageVector get() = Icons.Filled.PlayArrow
    val Pause: ImageVector get() = Icons.Filled.Pause
    val FastForward: ImageVector get() = Icons.Filled.FastForward
    val FastRewind: ImageVector get() = Icons.Filled.FastRewind

    // Rider, Squad & Invites
    val Person: ImageVector get() = Icons.Filled.Person
    val PersonAdd: ImageVector get() = Icons.Filled.PersonAdd
    val PersonRemove: ImageVector get() = Icons.Filled.PersonRemove
    val PersonSearch: ImageVector get() = Icons.Filled.PersonSearch
    val AccountCircle: ImageVector get() = Icons.Filled.AccountCircle
    val Group: ImageVector get() = Icons.Filled.Group
    val GroupAdd: ImageVector get() = Icons.Filled.GroupAdd
    val Groups: ImageVector get() = Icons.Filled.Groups
    val Badge: ImageVector get() = Icons.Filled.Badge
    val MeetingRoom: ImageVector get() = Icons.Filled.MeetingRoom
    val Login: ImageVector get() = Icons.Filled.Login
    val Logout: ImageVector get() = Icons.Filled.Logout
    val Link: ImageVector get() = Icons.Filled.Link
    val Mail: ImageVector get() = Icons.Filled.Mail
    val MailOutline: ImageVector get() = Icons.Filled.MailOutline
    val MarkEmailRead: ImageVector get() = Icons.Filled.MarkEmailRead
    val MarkEmailUnread: ImageVector get() = Icons.Filled.MarkEmailUnread
    val Email: ImageVector get() = Icons.Filled.Email
    val AlternateEmail: ImageVector get() = Icons.Filled.AlternateEmail
    val NotificationsNone: ImageVector get() = Icons.Filled.NotificationsNone

    // Actions, Forms & Settings
    val Add: ImageVector get() = Icons.Filled.Add
    val Close: ImageVector get() = Icons.Filled.Close
    val Check: ImageVector get() = Icons.Filled.Check
    val CheckCircle: ImageVector get() = Icons.Filled.CheckCircle
    val CheckCircleOutline: ImageVector get() = Icons.Filled.CheckCircleOutline
    val Clear: ImageVector get() = Icons.Filled.Clear
    val Refresh: ImageVector get() = Icons.Filled.Refresh
    val Edit: ImageVector get() = Icons.Filled.Edit
    val Save: ImageVector get() = Icons.Filled.Save
    val Share: ImageVector get() = Icons.Filled.Share
    val ContentCopy: ImageVector get() = Icons.Filled.ContentCopy
    val DeleteOutline: ImageVector get() = Icons.Filled.DeleteOutline
    val RemoveCircleOutline: ImageVector get() = Icons.Filled.RemoveCircleOutline
    val Search: ImageVector get() = Icons.Filled.Search
    val Notes: ImageVector get() = Icons.Filled.Notes
    val Visibility: ImageVector get() = Icons.Filled.Visibility
    val VisibilityOff: ImageVector get() = Icons.Filled.VisibilityOff
    val Settings: ImageVector get() = Icons.Filled.Settings
    val Tune: ImageVector get() = Icons.Filled.Tune
    val Build: ImageVector get() = Icons.Filled.Build
    val Language: ImageVector get() = Icons.Filled.Language
    val DarkMode: ImageVector get() = Icons.Filled.DarkMode
    val LightMode: ImageVector get() = Icons.Filled.LightMode
    val Bolt: ImageVector get() = Icons.Filled.Bolt
    val Star: ImageVector get() = Icons.Filled.Star
    val AutoAwesome: ImageVector get() = Icons.Filled.AutoAwesome
    val BookmarkAdded: ImageVector get() = Icons.Filled.BookmarkAdded

    // Updates & Sync
    val Download: ImageVector get() = Icons.Filled.Download
    val Downloading: ImageVector get() = Icons.Filled.Downloading
    val DownloadDone: ImageVector get() = Icons.Filled.DownloadDone
    val SystemUpdate: ImageVector get() = Icons.Filled.SystemUpdate
    val InstallMobile: ImageVector get() = Icons.Filled.InstallMobile
    val CloudDone: ImageVector get() = Icons.Filled.CloudDone
}
