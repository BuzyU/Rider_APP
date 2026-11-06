package com.ridervoice.mesh.switching

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.ridervoice.mesh.MeshLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class NetCaps(
    val hasInternet: Boolean,
    val isValidated: Boolean,
    val isWifi: Boolean,
    val isCellular: Boolean
)

fun interface NetworkCapsReader {
    fun read(): NetCaps?
}

class AndroidNetworkCapsReader(context: Context) : NetworkCapsReader {
    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun read(): NetCaps? {
        return try {
            val manager = cm ?: return null
            val net = manager.activeNetwork ?: return null
            val c = manager.getNetworkCapabilities(net) ?: return null
            NetCaps(
                hasInternet = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isValidated = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                isWifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                isCellular = c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            )
        } catch (e: SecurityException) {
            MeshLog.w("ValidatedNet", "caps read denied: ${e.javaClass.simpleName}")
            null
        } catch (e: RuntimeException) {
            MeshLog.w("ValidatedNet", "caps read runtime exception: ${e.javaClass.simpleName}")
            null
        }
    }
}

/** True only when the ACTIVE network claims internet AND Android validated it. Dead hotspot => false. */
open class ValidatedInternetSource(
    private val reader: NetworkCapsReader,
    private val context: Context?
) {
    constructor(reader: NetworkCapsReader) : this(reader, null)

    fun isValidated(): Boolean {
        return try {
            val c = reader.read() ?: return false
            c.hasInternet && c.isValidated
        } catch (e: SecurityException) {
            MeshLog.w("ValidatedNet", "isValidated denied: ${e.javaClass.simpleName}")
            false
        } catch (e: RuntimeException) {
            MeshLog.w("ValidatedNet", "isValidated runtime exception: ${e.javaClass.simpleName}")
            false
        }
    }

    open val validatedFlow: Flow<Boolean> = callbackFlow {
        val cm = context?.applicationContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            trySend(isValidated())
            awaitClose {}
            return@callbackFlow
        }

        trySend(isValidated())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(isValidated())
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            }
        } catch (e: SecurityException) {
            MeshLog.w("ValidatedNet", "callback register denied: ${e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            MeshLog.w("ValidatedNet", "callback register failed: ${e.javaClass.simpleName}")
        }

        awaitClose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: RuntimeException) {
            }
        }
    }
}
