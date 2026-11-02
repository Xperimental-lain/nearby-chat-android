package dev.nearbychat.transport

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class IncomingFileOffer(val senderId: String, val sender: String, val name: String, val mime: String, val size: Long)

class LanTransport(context: Context, private val listener: Listener) {
    interface Listener {
        fun onPeer(peer: ChatPeer)
        fun onPeerLost(id: String, route: Route)
        fun onMessage(message: ChatMessage)
        fun onGroupMessage(message: GroupChatEnvelope)
        fun requestFileApproval(offer: IncomingFileOffer, respond: (Boolean) -> Unit)
        fun onFileReceived(senderId: String, sender: String, file: File, mime: String)
        fun onFileSent(name: String, byteCount: Long)
        fun onStatus(text: String)
    }

    companion object {
        private const val SERVICE_TYPE = "_nearbychat._tcp."
        private const val SERVICE_PREFIX = "NearbyChat-"
        private const val SOCKET_TIMEOUT_MS = 120_000
        private const val RECEIVED_STORAGE_LIMIT = 200L * 1024 * 1024
        private const val MAX_ACTIVE_CONNECTIONS = 4
    }

    private val appContext = context.applicationContext
    private val deviceId = DeviceIdentity.id()
    private val deviceName = DeviceIdentity.displayName()
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val peers = ConcurrentHashMap<String, ChatPeer>()
    private val unresolvedServices = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolvingService = AtomicBoolean(false)
    private val storageLock = Any()
    private val connectionSlots = Semaphore(MAX_ACTIVE_CONNECTIONS)
    private var reservedStorage = 0L
    @Volatile private var closed = false
    @Volatile private var refreshAfterStop = false
    private var server: ServerSocket? = null
    private var serviceName: String? = null
    @Volatile private var discoveryStarted = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun start() {
        closed = false
        thread(name = "nearbychat-lan-server") {
            try {
                val socketServer = ServerSocket(0)
                server = socketServer
                registerService(socketServer.localPort)
                while (!closed) {
                    val socket = socketServer.accept()
                    if (!connectionSlots.tryAcquire()) {
                        socket.close()
                    } else {
                        thread(name = "nearbychat-lan-client") {
                            try { readConnection(socket) }
                            finally { connectionSlots.release() }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!closed) listener.onStatus("Local Wi-Fi listener stopped: ${e.message ?: "network error"}")
            }
        }
        startDiscovery()
    }

    fun refresh() {
        peers.keys.toList().forEach { listener.onPeerLost(it, Route.LOCAL_WIFI) }
        peers.clear()
        unresolvedServices.clear()
        if (discoveryStarted) {
            refreshAfterStop = true
            discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) }.onFailure { listener.onStatus("Could not refresh Wi-Fi discovery") } }
        } else startDiscovery()
    }

    fun send(peer: ChatPeer, text: String) {
        val host = peer.wifiHost ?: return listener.onStatus("That peer is no longer reachable on Wi-Fi")
        val port = peer.wifiPort ?: return listener.onStatus("That peer is no longer reachable on Wi-Fi")
        thread(name = "nearbychat-lan-send") {
            try {
                Socket(host, port).use { socket ->
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    output.write(WireFormat.encodeHello(deviceId, deviceName))
                    output.flush()
                    val hello = WireFormat.decode(WireFormat.readLine(input) ?: "") as? WireFormat.Frame.Hello
                        ?: throw IllegalStateException("Peer did not complete the Nearby Chat handshake")
                    listener.onPeer(ChatPeer(hello.id, hello.name, host, port))
                    output.write(WireFormat.encodeText(deviceId, deviceName, text))
                    output.flush()
                }
                listener.onMessage(ChatMessage(deviceId, deviceName, text, "Wi-Fi", incoming = false))
            } catch (e: Exception) {
                listener.onStatus("Wi-Fi send failed: ${e.message ?: "peer unreachable"}")
            }
        }
    }

    /** Sends one group post to every currently discovered Wi-Fi peer on this local network. */
    fun sendGroupMessage(groupId: String, groupName: String, encrypted: Boolean, payload: String) {
        val targets = peers.values.filter { it.hasWifi }.distinctBy { it.id }
        if (targets.isEmpty()) {
            listener.onStatus("No nearby Wi-Fi peers are available for this group")
            return
        }
        thread(name = "nearbychat-group-send") {
            var delivered = 0
            targets.forEach { peer ->
                try {
                    val host = peer.wifiHost ?: return@forEach
                    val port = peer.wifiPort ?: return@forEach
                    var sent = false
                    Socket(host, port).use { socket ->
                        socket.soTimeout = SOCKET_TIMEOUT_MS
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        output.write(WireFormat.encodeHello(deviceId, deviceName)); output.flush()
                        val hello = WireFormat.decode(WireFormat.readLine(input) ?: "") as? WireFormat.Frame.Hello ?: return@use
                        listener.onPeer(ChatPeer(hello.id, hello.name, host, port))
                        output.write(WireFormat.encodeGroupText(deviceId, deviceName, groupId, groupName, encrypted, payload))
                        output.flush()
                        sent = true
                    }
                    if (sent) delivered++
                } catch (_: Exception) { }
            }
            if (delivered == 0) listener.onStatus("Group message could not reach any nearby Wi-Fi peers")
        }
    }

    fun sendFile(peer: ChatPeer, uri: Uri) {
        val host = peer.wifiHost ?: return listener.onStatus("That peer is no longer reachable on Wi-Fi")
        val port = peer.wifiPort ?: return listener.onStatus("That peer is no longer reachable on Wi-Fi")
        thread(name = "nearbychat-file-send") {
            var staged: File? = null
            try {
                val meta = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && !cursor.isNull(column)) cursor.getString(column) else null
                    }
                }
                val fileName = File(meta ?: "attachment").name.take(120).ifBlank { "attachment" }
                val mime = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
                val dir = File(appContext.cacheDir, "outgoing").apply { mkdirs() }
                staged = File.createTempFile("send-", ".part", dir)
                var size = 0L
                appContext.contentResolver.openInputStream(uri)?.use { source ->
                    FileOutputStream(staged!!).use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            size += count
                            if (size > WireFormat.MAX_FILE_BYTES) throw IllegalArgumentException("Attachment exceeds 100 MB")
                            sink.write(buffer, 0, count)
                        }
                    }
                } ?: throw IllegalStateException("Could not open attachment")

                Socket(host, port).use { socket ->
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    output.write(WireFormat.encodeHello(deviceId, deviceName))
                    output.flush()
                    val hello = WireFormat.decode(WireFormat.readLine(input) ?: "") as? WireFormat.Frame.Hello
                        ?: throw IllegalStateException("Peer did not complete the Nearby Chat handshake")
                    listener.onPeer(ChatPeer(hello.id, hello.name, host, port))
                    output.write(WireFormat.encodeFileHeader(deviceId, deviceName, fileName, mime, size))
                    output.flush()
                    val ready = WireFormat.decode(WireFormat.readLine(input) ?: "") as? WireFormat.Frame.Control
                    if (ready?.action != "READY" || ready.value != "OK") throw IllegalStateException(ready?.value ?: "Recipient did not approve the file")
                    FileInputStream(staged!!).use { source -> source.copyTo(output, 64 * 1024) }
                    output.flush()
                    val done = WireFormat.decode(WireFormat.readLine(input) ?: "") as? WireFormat.Frame.Control
                    if (done?.action != "DONE" || done.value != "OK") throw IllegalStateException(done?.value ?: "Recipient did not complete the file")
                }
                listener.onFileSent(fileName, size)
            } catch (e: Exception) {
                listener.onStatus("Wi-Fi attachment failed: ${e.message ?: "peer unreachable"}")
            } finally {
                staged?.delete()
            }
        }
    }

    private fun readConnection(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val hello = WireFormat.decode(WireFormat.readLine(input) ?: "") as? WireFormat.Frame.Hello ?: return
                if (hello.id == deviceId) return
                peers[hello.id] = ChatPeer(hello.id, hello.name, socket.inetAddress.hostAddress, servicePortFor(hello.id))
                listener.onPeer(peers[hello.id]!!)
                output.write(WireFormat.encodeHello(deviceId, deviceName))
                output.flush()

                when (val frame = WireFormat.decode(WireFormat.readLine(input) ?: "")) {
                    is WireFormat.Frame.Text -> {
                        if (frame.id != hello.id) return
                        listener.onMessage(ChatMessage(frame.id, frame.name, frame.text, "Wi-Fi", incoming = true))
                    }
                    is WireFormat.Frame.GroupText -> {
                        if (frame.id != hello.id) return
                        listener.onGroupMessage(GroupChatEnvelope(frame.id, frame.name, frame.groupId, frame.groupName, frame.encrypted, frame.payload))
                    }
                    is WireFormat.Frame.File -> receiveFile(input, output, hello, frame)
                    else -> Unit
                }
            } catch (_: Exception) { }
        }
    }

    private fun receiveFile(input: java.io.InputStream, output: java.io.OutputStream, hello: WireFormat.Frame.Hello, frame: WireFormat.Frame.File) {
        if (frame.id != hello.id || !reserveStorage(frame.size)) {
            output.write(WireFormat.encodeControl("READY", "REJECTED")); output.flush()
            return
        }
        val accepted = CountDownLatch(1)
        var approved = false
        listener.requestFileApproval(IncomingFileOffer(frame.id, frame.name, frame.fileName, frame.mime, frame.size)) { decision ->
            approved = decision
            accepted.countDown()
        }
        val directory = File(appContext.cacheDir, "received").apply { mkdirs() }
        var temp: File? = null
        try {
            if (!accepted.await(60, TimeUnit.SECONDS) || !approved) {
                output.write(WireFormat.encodeControl("READY", "REJECTED")); output.flush()
                return
            }
            output.write(WireFormat.encodeControl("READY", "OK")); output.flush()
            val safeName = File(frame.fileName).name.take(120).ifBlank { "attachment" }
            temp = File.createTempFile("incoming-", ".part", directory)
            FileOutputStream(temp).use { sink ->
                val buffer = ByteArray(64 * 1024)
                var remaining = frame.size
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw IllegalStateException("Attachment ended before transfer completed")
                    sink.write(buffer, 0, count)
                    remaining -= count
                }
            }
            val target = File(directory, "${System.nanoTime()}-$safeName")
            if (!temp.renameTo(target)) throw IllegalStateException("Could not finalize received file")
            temp = null
            output.write(WireFormat.encodeControl("DONE", "OK")); output.flush()
            listener.onFileReceived(frame.id, frame.name, target, frame.mime)
        } catch (e: Exception) {
            output.write(WireFormat.encodeControl("DONE", "FAILED")); output.flush()
            throw e
        } finally {
            temp?.delete()
            releaseStorage(frame.size)
        }
    }

    private fun reserveStorage(size: Long): Boolean = synchronized(storageLock) {
        val directory = File(appContext.cacheDir, "received")
        val existing = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L
        if (size !in 0..WireFormat.MAX_FILE_BYTES || existing + reservedStorage + size > RECEIVED_STORAGE_LIMIT) return@synchronized false
        reservedStorage += size
        true
    }

    private fun releaseStorage(size: Long) = synchronized(storageLock) { reservedStorage = (reservedStorage - size).coerceAtLeast(0) }

    private fun registerService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_PREFIX + deviceId
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                serviceName = serviceInfo.serviceName
                listener.onStatus("Listening on this Wi-Fi network")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { listener.onStatus("Wi-Fi registration failed ($errorCode)") }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = registration
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    private fun startDiscovery() {
        if (closed) return
        try {
            if (multicastLock == null) multicastLock = wifi.createMulticastLock("nearbychat-nsd").apply { setReferenceCounted(false); acquire() }
            val discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) { discoveryStarted = true }
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (!service.serviceName.startsWith(SERVICE_PREFIX) || service.serviceName == serviceName) return
                    unresolvedServices.offer(service)
                    resolveNextService()
                }
                override fun onServiceLost(service: NsdServiceInfo) {
                    val id = service.serviceName.removePrefix(SERVICE_PREFIX)
                    peers.remove(id)
                    listener.onPeerLost(id, Route.LOCAL_WIFI)
                }
                override fun onDiscoveryStopped(serviceType: String) {
                    discoveryStarted = false
                    if (refreshAfterStop) { refreshAfterStop = false; startDiscovery() }
                }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discoveryStarted = false; listener.onStatus("Wi-Fi discovery failed ($errorCode)") }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    discoveryStarted = true
                    refreshAfterStop = false
                    listener.onStatus("Could not refresh Wi-Fi discovery ($errorCode)")
                }
            }
            discoveryListener = discovery
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) { listener.onStatus("Wi-Fi discovery unavailable: ${e.message ?: "error"}") }
    }

    private fun resolveNextService() {
        if (!resolvingService.compareAndSet(false, true)) return
        val service = unresolvedServices.poll()
        if (service == null) { resolvingService.set(false); return }
        try {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { resolvingService.set(false); resolveNextService() }
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val id = resolved.serviceName.removePrefix(SERVICE_PREFIX)
                    val address: InetAddress? = resolved.host
                    if (id != deviceId && address != null) {
                        val peer = ChatPeer(id, "Nearby phone", address.hostAddress, resolved.port)
                        peers[id] = peer
                        listener.onPeer(peer)
                    }
                    resolvingService.set(false)
                    resolveNextService()
                }
            })
        } catch (_: Exception) { resolvingService.set(false); resolveNextService() }
    }

    private fun servicePortFor(id: String): Int? = peers[id]?.wifiPort

    fun close() {
        closed = true
        runCatching { server?.close() }
        server = null
        if (discoveryStarted) discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }
}
