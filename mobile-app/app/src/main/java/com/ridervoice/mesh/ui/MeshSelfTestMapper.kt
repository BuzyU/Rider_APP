package com.ridervoice.mesh.ui

import com.ridervoice.mesh.link.MeshLinkState

data class MeshSelfTestInput(
    val linkState: MeshLinkState,
    val localIp: String?,
    val broadcastAddress: String?,
    val authenticatedNeighboursHeardLast10s: Int,
    val secondsWithZeroNeighbours: Long
)

data class MeshSelfTestUiState(
    val linkStateText: String,
    val localAddressesText: String,
    val authenticatedNeighboursCount: Int,
    val isolationWarningHint: String?
)

object MeshSelfTestMapper {
    const val ZERO_NEIGHBOURS_HINT_THRESHOLD_SECONDS = 20L
    const val HINT_TEXT = "No nearby riders on this network. Are you on the same hotspot/Wi-Fi? Some networks block device-to-device traffic."

    fun map(input: MeshSelfTestInput, noneFallback: String = "-"): MeshSelfTestUiState {
        val linkText = when (input.linkState) {
            MeshLinkState.RUNNING -> "RUNNING"
            MeshLinkState.STARTING -> "STARTING"
            MeshLinkState.FAILED -> "FAILED"
            MeshLinkState.STOPPED -> "STOPPED"
        }
        val ipText = input.localIp?.takeIf { it.isNotBlank() } ?: noneFallback
        val bcastText = input.broadcastAddress?.takeIf { it.isNotBlank() } ?: noneFallback
        val addrText = "Local: $ipText | Broadcast: $bcastText"

        val warningHint = if (input.authenticatedNeighboursHeardLast10s == 0 &&
            input.secondsWithZeroNeighbours > ZERO_NEIGHBOURS_HINT_THRESHOLD_SECONDS
        ) {
            HINT_TEXT
        } else {
            null
        }

        return MeshSelfTestUiState(
            linkStateText = linkText,
            localAddressesText = addrText,
            authenticatedNeighboursCount = input.authenticatedNeighboursHeardLast10s,
            isolationWarningHint = warningHint
        )
    }
}

