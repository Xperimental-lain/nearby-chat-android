package dev.nearbychat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.nearbychat.core.AvailablePaths
import dev.nearbychat.core.OutgoingPayload
import dev.nearbychat.core.Transport
import dev.nearbychat.core.TransportRouter
import dev.nearbychat.transport.BluetoothTransport
import dev.nearbychat.transport.ChatMessage
import dev.nearbychat.transport.ChatPeer
import dev.nearbychat.transport.DeviceIdentity
import dev.nearbychat.transport.IncomingFileOffer
import dev.nearbychat.transport.LanTransport
import dev.nearbychat.transport.Route
import java.io.File

class MainActivity : Activity() {
    private val peers = linkedMapOf<String, ChatPeer>()
    private val router = TransportRouter()
    private var selectedPeerId: String? = null
    private var lan: LanTransport? = null
    private var bluetooth: BluetoothTransport? = null
    private lateinit var status: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var messages: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var senderName: String

    private val transportListener = object : LanTransport.Listener, BluetoothTransport.Listener {
        override fun onPeer(peer: ChatPeer) = runOnUiThread {
            val old = peers[peer.id]
            peers[peer.id] = ChatPeer(
                id = peer.id,
                name = if (peer.name == "Nearby phone") old?.name ?: peer.name else peer.name,
                wifiHost = peer.wifiHost ?: old?.wifiHost,
                wifiPort = peer.wifiPort ?: old?.wifiPort,
                bluetoothAddress = peer.bluetoothAddress ?: old?.bluetoothAddress,
            )
            renderPeers()
        }

        override fun onPeerLost(id: String, route: Route) = runOnUiThread {
            val peer = peers[id] ?: return@runOnUiThread
            val updated = when (route) {
                Route.LOCAL_WIFI -> peer.copy(wifiHost = null, wifiPort = null)
                Route.BLUETOOTH -> peer.copy(bluetoothAddress = null)
            }
            if (!updated.hasWifi && !updated.hasBluetooth) peers.remove(id) else peers[id] = updated
            if (selectedPeerId == id && !peers.containsKey(id)) selectedPeerId = null
            renderPeers()
        }

        override fun onMessage(message: ChatMessage) = runOnUiThread {
            peers[message.senderId]?.let { peers[message.senderId] = it.copy(name = message.sender) }
            renderPeers()
            renderMessage(message)
        }

        override fun requestFileApproval(offer: IncomingFileOffer, respond: (Boolean) -> Unit) = runOnUiThread {
            if (isFinishing || isDestroyed) { respond(false); return@runOnUiThread }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Incoming file")
                .setMessage("${offer.sender} wants to send ${offer.name} (${formatSize(offer.size)}). This sender has not been cryptographically verified.")
                .setNegativeButton("Decline") { _, _ -> respond(false) }
                .setPositiveButton("Accept") { _, _ -> respond(true) }
                .setOnCancelListener { respond(false) }
                .show()
        }

        override fun onFileReceived(senderId: String, sender: String, file: File, mime: String) = runOnUiThread {
            onPeer(ChatPeer(senderId, sender))
            appendText("$sender sent ${file.name} ($mime)")
            val openButton = Button(this@MainActivity).apply {
                text = "Open ${file.name}"
                setOnClickListener { openReceivedFile(file, mime) }
            }
            val deleteButton = Button(this@MainActivity).apply {
                text = "Delete ${file.name}"
                setOnClickListener {
                    if (file.delete()) {
                        messages.removeView(openButton)
                        messages.removeView(this)
                        Toast.makeText(this@MainActivity, "Received file deleted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            messages.addView(openButton)
            messages.addView(deleteButton)
        }

        override fun onFileSent(name: String, byteCount: Long) = runOnUiThread {
            appendText("You sent $name (${formatSize(byteCount)}) over Wi-Fi")
        }

        override fun onStatus(text: String) = runOnUiThread { status.text = text }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        senderName = DeviceIdentity.displayName()
        buildUi()
        requestNearbyPermissionsAndStart()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 12)
        }
        root.addView(TextView(this).apply {
            text = "Nearby Chat · Android prototype"
            textSize = 22f
            setTextColor(0xFF17202A.toInt())
        })
        status = TextView(this).apply {
            text = "Start nearby mode on both phones. Wi-Fi requires the same local network; Bluetooth requires pairing in Android settings."
            textSize = 14f
            setPadding(0, 10, 0, 10)
        }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "Prototype warning: Wi-Fi traffic is not end-to-end encrypted. Accept files only from people you trust."
            textSize = 12f
            setTextColor(0xFF9A4D00.toInt())
        })
        root.addView(Button(this).apply {
            text = "Start / refresh nearby"
            setOnClickListener { requestNearbyPermissionsAndStart() }
        })
        root.addView(TextView(this).apply { text = "PEERS · select a recipient"; setPadding(0, 12, 0, 4) })
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(peerList)
        root.addView(TextView(this).apply { text = "CHAT"; setPadding(0, 12, 0, 4) })
        val scroll = ScrollView(this)
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4, 0, 4) }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        messageInput = EditText(this).apply {
            hint = "Message"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { sendCurrentMessage(); true } else false
            }
        }
        composer.addView(messageInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(Button(this).apply {
            text = "Attach"
            setOnClickListener {
                if (peers[selectedPeerId]?.hasWifi != true) {
                    Toast.makeText(this@MainActivity, "Choose a peer with Wi-Fi to send an attachment", Toast.LENGTH_SHORT).show()
                } else {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, REQUEST_FILE)
                }
            }
        })
        composer.addView(Button(this).apply { text = "Send"; setOnClickListener { sendCurrentMessage() } })
        root.addView(composer)
        setContentView(root)
        renderPeers()
    }

    private fun hasBluetoothPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasWifiPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

    private fun requestNearbyPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 && !hasBluetoothPermission()) needed += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 && !hasWifiPermission()) needed += Manifest.permission.NEARBY_WIFI_DEVICES
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQUEST_NEARBY)
        else startTransports(refresh = true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NEARBY) startTransports(refresh = true)
    }

    private fun startTransports(refresh: Boolean) {
        if (hasWifiPermission()) {
            if (lan == null) lan = LanTransport(applicationContext, transportListener).also { it.start() }
            else if (refresh) lan?.refresh()
        } else status.text = "Allow Nearby devices for Wi-Fi discovery."

        if (hasBluetoothPermission()) {
            if (bluetooth == null) bluetooth = BluetoothTransport(transportListener).also { it.start() }
            else if (refresh) bluetooth?.refresh()
        }
    }

    @Deprecated("Activity result compatibility for the Android MVP")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val peer = selectedPeerId?.let(peers::get) ?: return
            lan?.sendFile(peer, uri)
        }
    }

    private fun renderPeers() {
        peerList.removeAllViews()
        if (peers.isEmpty()) {
            peerList.addView(TextView(this).apply { text = "No peers found yet."; textSize = 14f })
            return
        }
        peers.values.forEach { peer ->
            val routes = buildList {
                if (peer.hasBluetooth) add("Bluetooth")
                if (peer.hasWifi) add("Wi-Fi")
            }.joinToString(" + ")
            peerList.addView(Button(this).apply {
                text = (if (selectedPeerId == peer.id) "✓ " else "") + "${peer.name} · $routes"
                textSize = 13f
                setOnClickListener { selectedPeerId = peer.id; renderPeers() }
            })
        }
    }

    private fun sendCurrentMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        val peer = selectedPeerId?.let(peers::get)
        if (peer == null) {
            Toast.makeText(this, "Select a peer first", Toast.LENGTH_SHORT).show()
            return
        }
        val transport = router.choose(
            OutgoingPayload(text.toByteArray(Charsets.UTF_8).size.toLong(), isMedia = false),
            AvailablePaths(bluetoothPeer = peer.hasBluetooth, localWifiPeer = peer.hasWifi),
        )
        messageInput.setText("")
        when (transport) {
            Transport.BLUETOOTH -> bluetooth?.send(peer, text) { lan?.send(peer, text) }
            Transport.LOCAL_WIFI -> lan?.send(peer, text)
            else -> Toast.makeText(this, "No message path is currently available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderMessage(message: ChatMessage) {
        appendText("${if (message.incoming) message.sender else "You"} · ${message.via}\n${message.text}")
    }

    private fun appendText(text: String) {
        messages.addView(TextView(this).apply { this.text = text; textSize = 15f; setPadding(0, 6, 0, 6) })
    }

    private fun openReceivedFile(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, "No app can open this file type", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatSize(size: Long): String = if (size < 1024 * 1024) "${size / 1024} KB" else "${size / (1024 * 1024)} MB"

    override fun onDestroy() {
        lan?.close()
        bluetooth?.close()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_NEARBY = 40
        private const val REQUEST_FILE = 41
    }
}
