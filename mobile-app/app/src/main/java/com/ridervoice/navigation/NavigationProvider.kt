package com.ridervoice.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri

data class Waypoint(val lat: Double, val lng: Double)

interface NavigationProvider {
    val name: String
    val isInstalled: (Context) -> Boolean
    fun buildNavigationIntent(destination: Waypoint): Intent
}

class GoogleMapsProvider : NavigationProvider {
    override val name = "Google Maps"
    
    override val isInstalled: (Context) -> Boolean = { context ->
        try {
            context.packageManager.getPackageInfo("com.google.android.apps.maps", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun buildNavigationIntent(destination: Waypoint): Intent {
        val uri = Uri.parse("google.navigation:q=${destination.lat},${destination.lng}")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
    }
}

class WazeProvider : NavigationProvider {
    override val name = "Waze"
    
    override val isInstalled: (Context) -> Boolean = { context ->
        try {
            context.packageManager.getPackageInfo("com.waze", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun buildNavigationIntent(destination: Waypoint): Intent {
        val uri = Uri.parse("waze://?ll=${destination.lat},${destination.lng}&navigate=yes")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.waze")
        }
    }
}

class OsmAndProvider : NavigationProvider {
    override val name = "OsmAnd"
    
    override val isInstalled: (Context) -> Boolean = { context ->
        try {
            context.packageManager.getPackageInfo("net.osmand.plus", 0)
            true
        } catch (e: Exception) {
            try {
                context.packageManager.getPackageInfo("net.osmand", 0)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    override fun buildNavigationIntent(destination: Waypoint): Intent {
        // OsmAnd uses standard geo URI
        val uri = Uri.parse("geo:${destination.lat},${destination.lng}?q=${destination.lat},${destination.lng}")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            // Can be either plus or free version, so we don't hardcode package
            // unless we specifically check which one is installed.
        }
    }
}

enum class NavigationState {
    IDLE,
    LAUNCHING,
    ACTIVE,
    FAILED
}

object NavigationDelegate {
    val providers = listOf(GoogleMapsProvider(), WazeProvider(), OsmAndProvider())
    
    // Session tracking
    private var currentState: NavigationState = NavigationState.IDLE
    
    fun getAvailableProviders(context: Context): List<NavigationProvider> {
        return providers.filter { it.isInstalled(context) }
    }
    
    fun launchNavigation(context: Context, provider: NavigationProvider, destination: Waypoint): Boolean {
        currentState = NavigationState.LAUNCHING
        return try {
            val intent = provider.buildNavigationIntent(destination)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
            currentState = NavigationState.ACTIVE
            true
        } catch (e: Exception) {
            currentState = NavigationState.FAILED
            false
        }
    }

    fun endSession() {
        currentState = NavigationState.IDLE
    }
    
    fun getSessionState(): NavigationState = currentState
}
