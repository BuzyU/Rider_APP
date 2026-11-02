package com.ridervoice.mesh.session

interface OfflineSecretProvider {
    fun getSecretForOnlineRide(roomId: String): ByteArray?
}

class NullOfflineSecretProvider : OfflineSecretProvider {
    override fun getSecretForOnlineRide(roomId: String): ByteArray? = null
}

