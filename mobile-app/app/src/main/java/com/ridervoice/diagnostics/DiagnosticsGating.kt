package com.ridervoice.diagnostics

/**
 * Pure policy gating for network diagnostics collection throughout a convoy ride.
 * Starts telemetry collection only when:
 * 1. ConnectionState is CONNECTED
 * 2. Diagnostics is enabled for the ride session (host flag)
 * 3. Rider has not opted out locally
 */
object DiagnosticsGating {
    fun shouldStartCollector(
        isConnected: Boolean,
        diagnosticsEnabled: Boolean,
        isRiderOptedOut: Boolean
    ): Boolean {
        return isConnected && diagnosticsEnabled && !isRiderOptedOut
    }
}
