package com.ridervoice.audio

enum class RouteType { PHONE, WIRED, BT_SCO, BT_LE }
enum class NoiseFilterLevel { LOW, MEDIUM, HIGH }

data class AudioProfile(
    val routeType: RouteType,
    val noiseSuppression: Boolean,
    val echoCancellation: Boolean,
    val autoGain: Boolean,
    val highPassHz: Float,
    val bitrate: Int,
    val dtx: Boolean,
    val voxOpenRatio: Float
) {
    companion object {
        fun forRoute(route: RouteType, filterLevel: NoiseFilterLevel = NoiseFilterLevel.MEDIUM): AudioProfile {
            val ratioDelta = when (filterLevel) {
                NoiseFilterLevel.LOW -> -0.4f
                NoiseFilterLevel.MEDIUM -> 0.0f
                NoiseFilterLevel.HIGH -> 0.6f
            }
            return when (route) {
                RouteType.BT_SCO -> AudioProfile(
                    routeType = RouteType.BT_SCO,
                    noiseSuppression = true,
                    echoCancellation = false,
                    autoGain = true,
                    highPassHz = 130f,
                    bitrate = 28000,
                    dtx = false,
                    voxOpenRatio = (3.2f + ratioDelta).coerceIn(2.0f, 5.0f)
                )
                RouteType.BT_LE -> AudioProfile(
                    routeType = RouteType.BT_LE,
                    noiseSuppression = true,
                    echoCancellation = false,
                    autoGain = true,
                    highPassHz = 130f,
                    bitrate = 32000,
                    dtx = false,
                    voxOpenRatio = (3.0f + ratioDelta).coerceIn(2.0f, 5.0f)
                )
                RouteType.WIRED -> AudioProfile(
                    routeType = RouteType.WIRED,
                    noiseSuppression = true,
                    echoCancellation = true,
                    autoGain = false,
                    highPassHz = 100f,
                    bitrate = 32000,
                    dtx = false,
                    voxOpenRatio = (2.8f + ratioDelta).coerceIn(2.0f, 5.0f)
                )
                RouteType.PHONE -> AudioProfile(
                    routeType = RouteType.PHONE,
                    noiseSuppression = true,
                    echoCancellation = true,
                    autoGain = true,
                    highPassHz = 100f,
                    bitrate = 32000,
                    dtx = false,
                    voxOpenRatio = (3.0f + ratioDelta).coerceIn(2.0f, 5.0f)
                )
            }
        }
    }
}
