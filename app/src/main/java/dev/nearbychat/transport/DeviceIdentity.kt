package dev.nearbychat.transport

import java.util.UUID

object DeviceIdentity {
    private val sessionId = UUID.randomUUID().toString()

    fun id(): String = sessionId

    fun displayName(): String = "Phone-${id().takeLast(4)}"
}
