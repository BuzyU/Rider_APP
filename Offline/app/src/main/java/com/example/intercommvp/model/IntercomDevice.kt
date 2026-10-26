package com.example.intercommvp.model

data class IntercomDevice(
    val id: String,
    val name: String,
    val transport: TransportKind,
    val online: Boolean,
    val address: String? = null
)

enum class TransportKind(val label: String) {
    WIFI("Wi‑Fi"),
    BLUETOOTH("Bluetooth"),
    NEARBY("Nearby")
}
