package com.ridervoice.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ridervoice.navigation.Routes

/**
 * Motion tokens and navigation transition policies (UX-01, UX-02).
 *
 * No other file may hard-code navigation animation durations; all route
 * transitions and reduce-motion checks flow through [RvMotion].
 */
@Immutable
object RvMotion {
    const val INSTANT: Int = 0
    const val QUICK: Int = 120
    const val STANDARD: Int = 220

    val Easing: Easing = FastOutSlowInEasing

    enum class RouteTransitionPolicy {
        /** Top-level shell tabs & entry screens: instant switch, zero fade lag. */
        TOP_LEVEL_TAB,

        /** Secondary drill-down screens: subtle <= 1/4 width horizontal slide + fade. */
        DRILL_DOWN,

        /** Ride-critical & emergency screens: instantaneous in both directions. */
        RIDE_CRITICAL
    }

    /**
     * Exhaustive route-to-transition policy table covering every route in [Routes].
     */
    val allRoutePolicies: Map<String, RouteTransitionPolicy> = mapOf(
        Routes.SPLASH to RouteTransitionPolicy.TOP_LEVEL_TAB,
        Routes.LOGIN to RouteTransitionPolicy.TOP_LEVEL_TAB,
        Routes.HOME to RouteTransitionPolicy.TOP_LEVEL_TAB,
        Routes.RIDE_STATS to RouteTransitionPolicy.TOP_LEVEL_TAB,
        Routes.SQUAD to RouteTransitionPolicy.TOP_LEVEL_TAB,
        Routes.SETTINGS to RouteTransitionPolicy.TOP_LEVEL_TAB,
        Routes.ACCOUNT to RouteTransitionPolicy.TOP_LEVEL_TAB,

        Routes.REGISTER to RouteTransitionPolicy.DRILL_DOWN,
        Routes.HOST_SETUP to RouteTransitionPolicy.DRILL_DOWN,
        Routes.INVITE_FRIENDS to RouteTransitionPolicy.DRILL_DOWN,
        Routes.LOBBY to RouteTransitionPolicy.DRILL_DOWN,
        Routes.INVITES_INBOX to RouteTransitionPolicy.DRILL_DOWN,
        Routes.DEVICE_SETUP to RouteTransitionPolicy.DRILL_DOWN,
        Routes.POST_RIDE_SUMMARY to RouteTransitionPolicy.DRILL_DOWN,
        Routes.SQUAD_REQUESTS to RouteTransitionPolicy.DRILL_DOWN,
        Routes.ROUTE_PLANNER to RouteTransitionPolicy.DRILL_DOWN,
        Routes.JOIN_ROOM to RouteTransitionPolicy.DRILL_DOWN,
        Routes.JOIN_VIA_TOKEN to RouteTransitionPolicy.DRILL_DOWN,
        Routes.RIDE_REPLAY to RouteTransitionPolicy.DRILL_DOWN,

        Routes.ACTIVE_RIDE_HUD to RouteTransitionPolicy.RIDE_CRITICAL,
        Routes.SOS to RouteTransitionPolicy.RIDE_CRITICAL,
        Routes.OFFLINE_MESH_LOBBY to RouteTransitionPolicy.RIDE_CRITICAL,
        Routes.OFFLINE_MESH_HUD to RouteTransitionPolicy.RIDE_CRITICAL
    )

    fun policyFor(route: String?): RouteTransitionPolicy {
        if (route == null) return RouteTransitionPolicy.TOP_LEVEL_TAB
        allRoutePolicies[route]?.let { return it }
        val base = route.substringBefore('/').substringBefore('?')
        return allRoutePolicies.entries.firstOrNull { (pattern, _) ->
            pattern.substringBefore('/').substringBefore('?') == base
        }?.value ?: RouteTransitionPolicy.TOP_LEVEL_TAB
    }

    /**
     * Reads the system "Remove animations" / animator scale setting.
     * Returns true when [Settings.Global.ANIMATOR_DURATION_SCALE] == 0f.
     */
    fun isReduceMotionEnabled(context: Context): Boolean {
        val scale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
        } catch (_: Exception) {
            1.0f
        }
        return scale == 0f
    }

    fun enterTransition(
        initialRoute: String?,
        targetRoute: String?,
        reduceMotion: Boolean
    ): EnterTransition {
        if (reduceMotion) return EnterTransition.None
        val initialPolicy = policyFor(initialRoute)
        val targetPolicy = policyFor(targetRoute)
        if (initialPolicy == RouteTransitionPolicy.RIDE_CRITICAL ||
            targetPolicy == RouteTransitionPolicy.RIDE_CRITICAL
        ) {
            return EnterTransition.None
        }
        return when (targetPolicy) {
            RouteTransitionPolicy.DRILL_DOWN ->
                slideInHorizontally(animationSpec = tween(STANDARD, easing = Easing)) { fullWidth ->
                    fullWidth / 4
                } + fadeIn(animationSpec = tween(STANDARD, easing = Easing))
            RouteTransitionPolicy.TOP_LEVEL_TAB,
            RouteTransitionPolicy.RIDE_CRITICAL -> EnterTransition.None
        }
    }

    fun exitTransition(
        initialRoute: String?,
        targetRoute: String?,
        reduceMotion: Boolean
    ): ExitTransition {
        if (reduceMotion) return ExitTransition.None
        val initialPolicy = policyFor(initialRoute)
        val targetPolicy = policyFor(targetRoute)
        if (initialPolicy == RouteTransitionPolicy.RIDE_CRITICAL ||
            targetPolicy == RouteTransitionPolicy.RIDE_CRITICAL
        ) {
            return ExitTransition.None
        }
        return when (targetPolicy) {
            RouteTransitionPolicy.DRILL_DOWN ->
                slideOutHorizontally(animationSpec = tween(STANDARD, easing = Easing)) { fullWidth ->
                    -fullWidth / 4
                } + fadeOut(animationSpec = tween(STANDARD, easing = Easing))
            RouteTransitionPolicy.TOP_LEVEL_TAB,
            RouteTransitionPolicy.RIDE_CRITICAL -> ExitTransition.None
        }
    }

    fun popEnterTransition(
        initialRoute: String?,
        targetRoute: String?,
        reduceMotion: Boolean
    ): EnterTransition {
        if (reduceMotion) return EnterTransition.None
        val initialPolicy = policyFor(initialRoute)
        val targetPolicy = policyFor(targetRoute)
        if (initialPolicy == RouteTransitionPolicy.RIDE_CRITICAL ||
            targetPolicy == RouteTransitionPolicy.RIDE_CRITICAL
        ) {
            return EnterTransition.None
        }
        return when (initialPolicy) {
            RouteTransitionPolicy.DRILL_DOWN ->
                slideInHorizontally(animationSpec = tween(STANDARD, easing = Easing)) { fullWidth ->
                    -fullWidth / 4
                } + fadeIn(animationSpec = tween(STANDARD, easing = Easing))
            RouteTransitionPolicy.TOP_LEVEL_TAB,
            RouteTransitionPolicy.RIDE_CRITICAL -> EnterTransition.None
        }
    }

    fun popExitTransition(
        initialRoute: String?,
        targetRoute: String?,
        reduceMotion: Boolean
    ): ExitTransition {
        if (reduceMotion) return ExitTransition.None
        val initialPolicy = policyFor(initialRoute)
        val targetPolicy = policyFor(targetRoute)
        if (initialPolicy == RouteTransitionPolicy.RIDE_CRITICAL ||
            targetPolicy == RouteTransitionPolicy.RIDE_CRITICAL
        ) {
            return ExitTransition.None
        }
        return when (initialPolicy) {
            RouteTransitionPolicy.DRILL_DOWN ->
                slideOutHorizontally(animationSpec = tween(STANDARD, easing = Easing)) { fullWidth ->
                    fullWidth / 4
                } + fadeOut(animationSpec = tween(STANDARD, easing = Easing))
            RouteTransitionPolicy.TOP_LEVEL_TAB,
            RouteTransitionPolicy.RIDE_CRITICAL -> ExitTransition.None
        }
    }
}

@Composable
fun rememberIsReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) { RvMotion.isReduceMotionEnabled(context) }
}

@Composable
fun rememberIsLifecycleResumed(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { owner, _ ->
            isResumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return isResumed
}
