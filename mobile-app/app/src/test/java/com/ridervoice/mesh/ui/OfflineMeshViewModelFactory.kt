package com.ridervoice.mesh.ui

import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun OfflineMeshViewModel(
    transport: OfflineMeshVoiceTransport,
    arbiter: AudioOwnershipArbiter
): OfflineMeshViewModel = OfflineMeshViewModel(
    transport = transport,
    arbiter = arbiter,
    holder = OfflineSessionHolder(),
    appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
)

fun OfflineMeshViewModel(
    transport: OfflineMeshVoiceTransport,
    arbiter: AudioOwnershipArbiter,
    appScope: CoroutineScope
): OfflineMeshViewModel = OfflineMeshViewModel(
    transport = transport,
    arbiter = arbiter,
    holder = OfflineSessionHolder(),
    appScope = appScope
)

fun OfflineMeshViewModel(
    transport: OfflineMeshVoiceTransport,
    arbiter: AudioOwnershipArbiter,
    holder: OfflineSessionHolder
): OfflineMeshViewModel = OfflineMeshViewModel(
    transport = transport,
    arbiter = arbiter,
    holder = holder,
    appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
)
