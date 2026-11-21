package com.ridervoice.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.ridervoice.navigation.DeepLinkHolder
import com.ridervoice.navigation.Routes

/**
 * Snapshot of launch-time conditions governing whether an animated/video splash should play
 * or be skipped immediately to the destination route.
 */
data class SplashLaunchContext(
    val isDeepLink: Boolean = false,
    val isPushLaunch: Boolean = false,
    val isShareLink: Boolean = false,
    val isActiveRideResume: Boolean = false,
    val isReducedMotion: Boolean = false,
    val isBatterySaver: Boolean = false,
    val isLowMemory: Boolean = false,
    val hasVideoAsset: Boolean = false
)

/**
 * Event-driven playback host contract for the splash screen.
 * Implementations MUST NOT request audio focus and MUST release all resources in [release].
 */
interface SplashPlayerHost {
    val requestsAudioFocus: Boolean
        get() = false

    fun start(
        onPrepared: () -> Unit,
        onCompleted: () -> Unit,
        onError: (Throwable?) -> Unit
    )

    fun release()
}

/**
 * Interim no-op host used while res/raw/splash_ride.mp4 is not yet bundled.
 * Navigates immediately with zero timers and zero audio focus.
 */
object NoOpSplashPlayerHost : SplashPlayerHost {
    override val requestsAudioFocus: Boolean = false

    override fun start(
        onPrepared: () -> Unit,
        onCompleted: () -> Unit,
        onError: (Throwable?) -> Unit
    ) {
        onCompleted()
    }

    override fun release() = Unit
}

object SplashSkipPolicy {

    /**
     * Returns true whenever splash playback must be bypassed and navigation must proceed immediately.
     */
    fun shouldSkipSplash(ctx: SplashLaunchContext): Boolean {
        return !ctx.hasVideoAsset ||
            ctx.isDeepLink ||
            ctx.isPushLaunch ||
            ctx.isShareLink ||
            ctx.isActiveRideResume ||
            ctx.isReducedMotion ||
            ctx.isBatterySaver ||
            ctx.isLowMemory
    }

    fun detectLaunchContext(
        context: Context,
        startRoute: String? = null,
        hasActiveRide: Boolean = false
    ): SplashLaunchContext {
        val hasPendingToken = !DeepLinkHolder.pendingToken.value.isNullOrBlank()
        val isJoinTokenRoute = startRoute?.startsWith("join_via_token") == true
        val isPushRoute = !startRoute.isNullOrBlank() && !isJoinTokenRoute

        val reducedMotion = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }

        val batterySaver = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode == true
        } catch (_: Exception) {
            false
        }

        val lowMemory = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.isLowRamDevice == true
        } catch (_: Exception) {
            false
        }

        val hasAsset = try {
            context.resources.getIdentifier("splash_ride", "raw", context.packageName) != 0
        } catch (_: Exception) {
            false
        }

        return SplashLaunchContext(
            isDeepLink = hasPendingToken || isJoinTokenRoute,
            isPushLaunch = isPushRoute,
            isShareLink = isJoinTokenRoute,
            isActiveRideResume = hasActiveRide || startRoute?.startsWith("active_ride") == true,
            isReducedMotion = reducedMotion,
            isBatterySaver = batterySaver,
            isLowMemory = lowMemory,
            hasVideoAsset = hasAsset
        )
    }
}
