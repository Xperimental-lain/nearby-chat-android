package dev.nearbychat.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Paired-device text transport over authenticated-at-the-radio-layer secure RFCOMM. */
class BluetoothTransport(private val listener: Listener) {
    interface Listener {
        fun onPeer(peer: ChatPeer)
        fun onPeerLost(id: String, route: Route)
        fun onMessage(message: ChatMessage)
        fun onStatus(text: String)
    }

    companion object {
        private const val SERVICE_NAME = "NearbyChat"
        private val SERVICE_UUID: UUID = UUID.fromString("b5f4b03c-69d2-4b1d-9e9a-20d9225f45a1")
    }

    private val deviceId = DeviceIdentity.id()
    private val deviceName = DeviceIdentity.displayName()
    @Volatile private var closed = false
    @Volatile private var server: BluetoothServerSocket? = null
    private val addressesById = ConcurrentHashMap<String, String>()
    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    fun start() {
        closed = false
        val bt = adapter ?: return listener.onStatus("This device has no Bluetooth adapter")
        try {
            if (!bt.isEnabled) return listener.onStatus("Turn on Bluetooth, then tap refresh")
            if (server != null) { probeBondedDevices(); return }
            thread(name = "nearbychat-bt-listener") {
                try {
                    val listening = bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                    server = listening
                    listener.onStatus("Bluetooth is ready for paired devices")
                    probeBondedDevices()
                    while (!closed) {
                        val socket = listening.accept()
                        thread(name = "nearbychat-bt-incoming") { readConnection(socket) }
                    }
                } catch (e: SecurityException) {
                    if (!closed) listener.onStatus("Allow Nearby devices permission for Bluetooth")
                } catch (e: Exception) {
                    server = null
                    if (!closed) listener.onStatus("Bluetooth listener stopped: ${e.message ?: "error"}")
                }
            }
        } catch (_: SecurityException) {
            listener.onStatus("Allow Nearby devices permission for Bluetooth")
        }
    }

    fun refresh() {
        val bt = adapter
        try {
            if (bt == null || !bt.isEnabled) {
                close()
                addressesById.keys.toList().forEach { listener.onPeerLost(it, Route.BLUETOOTH) }
                addressesById.clear()
                listener.onStatus("Turn on Bluetooth, then tap refresh")
                return
            }
            if (server == null) start() else probeBondedDevices()
        } catch (_: SecurityException) {
            listener.onStatus("Allow Nearby devices permission to refresh Bluetooth")
        }
    }

    private fun probeBondedDevices() {
        try {
            val devices = adapter?.bondedDevices.orEmpty()
            val addresses = devices.map { it.address }.toSet()
            addressesById.entries.removeIf { entry ->
                if (entry.value !in addresses) { listener.onPeerLost(entry.key, Route.BLUETOOTH); true } else false
            }
            devices.forEach { device ->
                thread(name = "nearbychat-bt-probe") { probe(device) }
            }
        } catch (_: SecurityException) {
            listener.onStatus("Allow Nearby devices permission to list paired phones")
        }
    }

    private fun probe(device: BluetoothDevice) {
        val connected = connect(device.address, reportFailure = false) { socket ->
            val hello = completeHello(socket) ?: throw IllegalStateException("Peer did not complete the Nearby Chat handshake")
            publishPeer(hello, device.address)
        }
        if (!connected) {
            addressesById.entries.filter { it.value == device.address }.forEach { entry ->
                if (addressesById.remove(entry.key, device.address)) listener.onPeerLost(entry.key, Route.BLUETOOTH)
            }
        }
    }

    fun send(peer: ChatPeer, text: String, fallback: (() -> Unit)? = null) {
        val address = peer.bluetoothAddress ?: return fallback?.invoke() ?: listener.onStatus("Bluetooth is unavailable for this peer")
        thread(name = "nearbychat-bt-send") {
            val sent = connect(address, reportFailure = true) { socket ->
                val hello = completeHello(socket) ?: throw IllegalStateException("Peer did not complete the Nearby Chat handshake")
                publishPeer(hello, address)
                socket.outputStream.write(WireFormat.encodeText(deviceId, deviceName, text))
                socket.outputStream.flush()
                listener.onMessage(ChatMessage(deviceId, deviceName, text, "Bluetooth", incoming = false))
            }
            if (!sent) {
                listener.onPeerLost(peer.id, Route.BLUETOOTH)
                if (peer.hasWifi) {
                    listener.onStatus("Bluetooth unavailable; retrying over Wi-Fi")
                    fallback?.invoke()
                }
            }
        }
    }

    private inline fun connect(address: String, reportFailure: Boolean, action: (BluetoothSocket) -> Unit): Boolean {
        var socket: BluetoothSocket? = null
        try {
            val bt = adapter ?: throw IllegalStateException("Bluetooth is unavailable")
            socket = bt.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERVICE_UUID)
            bt.cancelDiscovery()
            socket.connect()
            action(socket)
            return true
        } catch (e: SecurityException) {
            if (reportFailure) listener.onStatus("Allow Nearby devices permission for Bluetooth")
        } catch (e: Exception) {
            if (reportFailure) listener.onStatus("Bluetooth connection failed: ${e.message ?: "peer unreachable"}")
        } finally {
            runCatching { socket?.close() }
        }
        return false
    }

    private fun completeHello(socket: BluetoothSocket): WireFormat.Frame.Hello? {
        socket.outputStream.write(WireFormat.encodeHello(deviceId, deviceName))
        socket.outputStream.flush()
        return WireFormat.decode(WireFormat.readLine(socket.inputStream) ?: "") as? WireFormat.Frame.Hello
    }

    private fun readConnection(socket: BluetoothSocket) {
        socket.use {
            try {
                val input = socket.inputStream
                val output = socket.outputStream
                val hello = WireFormat.decode(WireFormat.readLine(input) ?: "") as? WireFormat.Frame.Hello ?: return
                if (hello.id == deviceId) return
                publishPeer(hello, socket.remoteDevice.address)
                output.write(WireFormat.encodeHello(deviceId, deviceName))
                output.flush()
                val frame = WireFormat.decode(WireFormat.readLine(input) ?: "")
                if (frame is WireFormat.Frame.Text && frame.id == hello.id) {
                    listener.onMessage(ChatMessage(frame.id, frame.name, frame.text, "Bluetooth", incoming = true))
                }
            } catch (_: Exception) { }
        }
    }

    private fun publishPeer(hello: WireFormat.Frame.Hello, address: String) {
        addressesById[hello.id] = address
        listener.onPeer(ChatPeer(hello.id, hello.name, bluetoothAddress = address))
    }

    fun close() {
        closed = true
        runCatching { server?.close() }
        server = null
    }
}
