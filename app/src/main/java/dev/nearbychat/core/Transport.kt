package dev.nearbychat.core

enum class Transport {
    BLUETOOTH,
    LOCAL_WIFI,
    WIFI_DIRECT,
}

data class AvailablePaths(
    val bluetoothPeer: Boolean = false,
    val localWifiPeer: Boolean = false,
    val wifiDirectPeer: Boolean = false,
)

data class OutgoingPayload(
    val byteCount: Long,
    val isMedia: Boolean,
) {
    init {
        require(byteCount >= 0) { "Payload size cannot be negative" }
    }
}

/** Chooses only a currently reachable path; transport implementations perform delivery. */
class TransportRouter(private val mediaWifiThresholdBytes: Long = 64 * 1024) {
    init {
        require(mediaWifiThresholdBytes > 0)
    }

    fun choose(payload: OutgoingPayload, paths: AvailablePaths): Transport? {
        val needsWifi = payload.isMedia || payload.byteCount >= mediaWifiThresholdBytes
        if (needsWifi) {
            return when {
                paths.localWifiPeer -> Transport.LOCAL_WIFI
                paths.wifiDirectPeer -> Transport.WIFI_DIRECT
                else -> null
            }
        }
        return when {
            paths.bluetoothPeer -> Transport.BLUETOOTH
            paths.localWifiPeer -> Transport.LOCAL_WIFI
            paths.wifiDirectPeer -> Transport.WIFI_DIRECT
            else -> null
        }
    }
}
