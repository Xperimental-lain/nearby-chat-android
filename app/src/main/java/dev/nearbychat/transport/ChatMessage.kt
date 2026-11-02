package dev.nearbychat.transport

data class ChatMessage(
    val senderId: String,
    val sender: String,
    val text: String,
    val via: String,
    val incoming: Boolean,
)

data class GroupChatEnvelope(
    val senderId: String,
    val sender: String,
    val groupId: String,
    val groupName: String,
    val encrypted: Boolean,
    val payload: String,
)

data class ChatPeer(
    val id: String,
    val name: String,
    val wifiHost: String? = null,
    val wifiPort: Int? = null,
    val bluetoothAddress: String? = null,
) {
    val hasWifi: Boolean get() = wifiHost != null && wifiPort != null
    val hasBluetooth: Boolean get() = bluetoothAddress != null
}

enum class Route { LOCAL_WIFI, BLUETOOTH }
