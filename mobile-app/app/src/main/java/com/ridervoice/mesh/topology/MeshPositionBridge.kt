package com.ridervoice.mesh.topology

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.ridervoice.mesh.MeshConfig
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

interface PositionSource {
    fun latestPosition(memberId: String): PositionFix?
}

object NoOpPositionSource : PositionSource {
    override fun latestPosition(memberId: String): PositionFix? = null
}

class AndroidPositionSource(context: Context) : PositionSource {
    private val appContext = context.applicationContext ?: context

    @SuppressLint("MissingPermission")
    override fun latestPosition(memberId: String): PositionFix? {
        val hasFine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null

        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val candidates = ArrayList<Location>(2)
        runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()?.let(candidates::add)
        runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()?.let(candidates::add)
        val best = candidates.minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE } ?: return null

        val accDeci = if (best.hasAccuracy()) {
            (best.accuracy * 10f).roundToInt().coerceIn(0, PositionFix.UNKNOWN_ACCURACY_DECIMETERS - 1)
        } else {
            PositionFix.UNKNOWN_ACCURACY_DECIMETERS
        }
        return PositionFix(
            memberId = memberId,
            timestampSec = (best.time / 1000L).toInt(),
            latE6 = (best.latitude * 1_000_000.0).roundToInt(),
            lonE6 = (best.longitude * 1_000_000.0).roundToInt(),
            accuracyDecimeters = accDeci
        )
    }
}

/**
 * Coordinates throttled `0x81` GNSS position sharing and fresher peer position snapshots
 * for [TopologyEngine] and [com.ridervoice.mesh.link.StarGroupOwnerSelector].
 */
class MeshPositionBridge(
    private val source: PositionSource = NoOpPositionSource,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxAgeMs: Long = 30_000L
) {
    private val remotePositions = ConcurrentHashMap<String, Pair<PositionFix, Long>>()
    @Volatile private var lastBroadcastMs: Long = 0L

    fun recordRemotePosition(fix: PositionFix) {
        if (fix.memberId.isBlank()) return
        remotePositions[fix.memberId] = fix to clock()
    }

    fun maybeBuildPositionBroadcast(localMemberId: String): ByteArray? {
        val now = clock()
        if (now - lastBroadcastMs < MeshConfig.POSITION_BROADCAST_INTERVAL_MS) return null
        val fix = source.latestPosition(localMemberId) ?: return null
        if (!fix.isAccurate()) return null
        lastBroadcastMs = now
        return MeshControlExtension.encodePosition(fix)
    }

    fun snapshotPositions(localMemberId: String?): Map<String, PositionFix> {
        val now = clock()
        remotePositions.entries.removeIf { now - it.value.second > maxAgeMs }
        val result = HashMap<String, PositionFix>(remotePositions.size + 1)
        remotePositions.forEach { (id, pair) ->
            result[id] = pair.first
        }
        if (!localMemberId.isNullOrBlank()) {
            source.latestPosition(localMemberId)?.takeIf { it.isAccurate() }?.let { localFix ->
                result[localMemberId] = localFix
            }
        }
        return result
    }

    fun clear() {
        remotePositions.clear()
        lastBroadcastMs = 0L
    }
}
