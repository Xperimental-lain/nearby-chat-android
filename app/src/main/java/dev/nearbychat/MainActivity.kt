package dev.nearbychat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
import dev.nearbychat.transport.GroupChatEnvelope
import dev.nearbychat.transport.GroupMessageCipher
import dev.nearbychat.transport.GroupRoom
import dev.nearbychat.transport.GroupRoomStore
import dev.nearbychat.transport.IncomingFileOffer
import dev.nearbychat.transport.LanTransport
import dev.nearbychat.transport.Route
import java.io.File

class MainActivity : Activity() {
    private val peers = linkedMapOf<String, ChatPeer>()
    private val router = TransportRouter()
    private val groupStore by lazy { GroupRoomStore(applicationContext) }
    private val groupHistory = linkedMapOf<String, MutableList<GroupLine>>()
    private var selectedPeerId: String? = null
    private var selectedGroupId: String? = null
    private var showingGroups = false
    private var lan: LanTransport? = null
    private var bluetooth: BluetoothTransport? = null
    private lateinit var status: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var messages: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var directConversationTitle: TextView
    private lateinit var groupList: LinearLayout
    private lateinit var groupMessages: LinearLayout
    private lateinit var groupMessageInput: EditText
    private lateinit var groupPanel: LinearLayout
    private lateinit var directPanel: LinearLayout
    private lateinit var panelContainer: FrameLayout
    private lateinit var activeGroupTitle: TextView
    private lateinit var inviteGroupButton: Button
    private lateinit var directTab: TextView
    private lateinit var groupsTab: TextView
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

        override fun onGroupMessage(message: GroupChatEnvelope) = runOnUiThread {
            val room = if (message.encrypted) {
                groupStore.find(message.groupId)?.takeIf { it.isPrivate }
            } else {
                groupStore.acceptPublicMessage(message.groupId, message.groupName)
            } ?: return@runOnUiThread
            val body = if (message.encrypted) runCatching { GroupMessageCipher.decrypt(room, message.payload) }.getOrNull() ?: return@runOnUiThread
            else message.payload
            if (body.length > 24_000) return@runOnUiThread
            addGroupLine(room.id, GroupLine(message.sender, body, incoming = true))
            renderGroups()
            if (selectedGroupId == room.id) renderGroupMessages()
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
            setPadding(dp(18), dp(14), dp(18), dp(10))
            setBackgroundColor(color("#F5F7FC"))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mark = FrameLayout(this).apply {
            background = rounded(color("#554EE8"), 18)
            elevation = dp(3).toFloat()
        }
        mark.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        header.addView(mark, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        brand.addView(label("Nearby Chat", 21f, "#17233E", bold = true))
        brand.addView(label("Messages, close to home", 12f, "#77819A"))
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(actionButton("Refresh", primary = false) { requestNearbyPermissionsAndStart() })
        root.addView(header)

        status = label("Starting nearby mode…", 12f, "#58647D").apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(color("#FFFFFF"), 14, color("#E7EAF2"))
        }
        root.addView(status, marginParams(top = 12, bottom = 10))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(color("#E9ECF5"), 18)
        }
        directTab = tabLabel("Chats") { showGroups(false) }
        groupsTab = tabLabel("Groups") { showGroups(true) }
        tabs.addView(directTab, LinearLayout.LayoutParams(0, dp(42), 1f))
        tabs.addView(groupsTab, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(tabs)

        val warning = label("Wi-Fi chats are not end-to-end encrypted. Private groups encrypt group text for people with the invite key, but sender identity is not verified.", 11f, "#855A22").apply {
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(color("#FFF5E2"), 12)
        }
        root.addView(warning, marginParams(top = 9, bottom = 10))

        panelContainer = FrameLayout(this)
        directPanel = buildDirectPanel()
        groupPanel = buildGroupPanel()
        panelContainer.addView(directPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panelContainer.addView(groupPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(panelContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showGroups(showingGroups)
        renderPeers()
        renderGroups()
    }

    private fun buildDirectPanel(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("NEARBY DEVICES", 11f, "#77819A", bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(label("Bluetooth  ·  Wi-Fi", 11f, "#77819A"))
        panel.addView(heading, marginParams(bottom = 5))
        val peerScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        peerScroll.addView(peerList)
        panel.addView(peerScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)))
        val divider = View(this).apply { setBackgroundColor(color("#E4E8F0")) }
        panel.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        directConversationTitle = label("Select a device to start chatting", 13f, "#53617D", bold = true)
        panel.addView(directConversationTitle, marginParams(top = 8, bottom = 2))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; smoothScrollTo(0, Int.MAX_VALUE) }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(8), dp(2), dp(8)) }
        scroll.addView(messages)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val composer = composerRow()
        messageInput = EditText(this).apply {
            hint = "Write a message"
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(color("#FFFFFF"), 22, color("#E4E8F0"))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { sendCurrentMessage(); true } else false
            }
        }
        composer.addView(messageInput, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(7) })
        composer.addView(actionButton("＋", false) {
            if (peers[selectedPeerId]?.hasWifi != true) Toast.makeText(this, "Choose a peer with Wi-Fi to send an attachment", Toast.LENGTH_SHORT).show()
            else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, REQUEST_FILE)
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(6) })
        composer.addView(actionButton("Send", true) { sendCurrentMessage() })
        panel.addView(composer, marginParams(top = 8, bottom = 2))
        return panel
    }

    private fun buildGroupPanel(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = ViewGroup.GONE }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(label("YOUR GROUPS", 11f, "#77819A", bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(actionButton("Join", false) { showJoinGroupDialog() }, LinearLayout.LayoutParams(dp(74), dp(40)).apply { marginEnd = dp(6) })
        actions.addView(actionButton("＋ Create", true) { showCreateGroupDialog() }, LinearLayout.LayoutParams(dp(112), dp(40)))
        panel.addView(actions, marginParams(bottom = 5))
        val listScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        groupList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll.addView(groupList)
        panel.addView(listScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)))
        val divider = View(this).apply { setBackgroundColor(color("#E4E8F0")) }
        panel.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val activeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        activeGroupTitle = label("Select a group", 16f, "#17233E", bold = true)
        activeRow.addView(activeGroupTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inviteGroupButton = actionButton("Invite", false) { selectedGroupId?.let { id -> groupStore.find(id)?.let(::showInviteDialog) } }
        activeRow.addView(inviteGroupButton)
        panel.addView(activeRow, marginParams(top = 8, bottom = 2))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        groupMessages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(8), dp(2), dp(8)) }
        scroll.addView(groupMessages)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val composer = composerRow()
        groupMessageInput = EditText(this).apply {
            hint = "Message the group"
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(color("#FFFFFF"), 22, color("#E4E8F0"))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { sendGroupMessage(); true } else false
            }
        }
        composer.addView(groupMessageInput, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(7) })
        composer.addView(actionButton("Send", true) { sendGroupMessage() })
        panel.addView(composer, marginParams(top = 8, bottom = 2))
        return panel
    }

    private fun composerRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun label(value: String, size: Float, hex: String, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color(hex))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun tabLabel(value: String, action: () -> Unit) = TextView(this).apply {
        text = value
        gravity = Gravity.CENTER
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setOnClickListener { action() }
    }

    private fun actionButton(value: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 13f
        isAllCaps = false
        setPadding(dp(10), 0, dp(10), 0)
        setTextColor(color(if (primary) "#FFFFFF" else "#4552C8"))
        background = rounded(color(if (primary) "#5453E8" else "#E9ECFF"), 18)
        elevation = if (primary) dp(2).toFloat() else 0f
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun color(hex: String) = Color.parseColor(hex)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun marginParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun showGroups(show: Boolean) {
        showingGroups = show
        if (::directPanel.isInitialized) directPanel.visibility = if (show) ViewGroup.GONE else ViewGroup.VISIBLE
        if (::groupPanel.isInitialized) groupPanel.visibility = if (show) ViewGroup.VISIBLE else ViewGroup.GONE
        if (::directTab.isInitialized) directTab.apply { setTextColor(color(if (show) "#77819A" else "#343BC2")); background = if (show) null else rounded(color("#FFFFFF"), 14) }
        if (::groupsTab.isInitialized) groupsTab.apply { setTextColor(color(if (show) "#343BC2" else "#77819A")); background = if (show) rounded(color("#FFFFFF"), 14) else null }
        if (show) renderGroups()
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
        val selectedPeer = selectedPeerId?.let(peers::get)
        directConversationTitle.text = selectedPeer?.let { "Chatting with ${it.name}" } ?: "Select a device to start chatting"
        if (peers.isEmpty()) {
            peerList.addView(label("No nearby devices yet · open Nearby Chat on another phone", 13f, "#8A93A8").apply {
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = rounded(color("#FFFFFF"), 14, color("#E7EAF2"))
            })
            return
        }
        peers.values.forEach { peer ->
            val routes = buildList {
                if (peer.hasBluetooth) add("Bluetooth")
                if (peer.hasWifi) add("Wi-Fi")
            }.joinToString(" + ")
            val selected = selectedPeerId == peer.id
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = rounded(color("#FFFFFF"), 14, color(if (selected) "#7476F4" else "#E7EAF2"))
                setOnClickListener { selectedPeerId = peer.id; renderPeers() }
            }
            card.addView(label("●", 12f, if (peer.hasWifi) "#36A77A" else "#E7A44A"), LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(label(peer.name, 14f, "#202C47", bold = true))
            details.addView(label(routes.ifBlank { "Nearby" }, 11f, "#858EA4"))
            card.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(label(if (selected) "●" else "›", 18f, "#6568DB"))
            peerList.addView(card, marginParams(bottom = 5))
        }
    }

    private fun renderGroups() {
        if (!::groupList.isInitialized) return
        groupList.removeAllViews()
        val rooms = groupStore.all()
        if (rooms.isEmpty()) {
            groupList.addView(label("No groups yet · create one or join with an invite", 13f, "#8A93A8").apply {
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = rounded(color("#FFFFFF"), 14, color("#E7EAF2"))
            })
        } else rooms.forEach { room ->
            val selected = selectedGroupId == room.id
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = rounded(color("#FFFFFF"), 14, color(if (selected) "#7476F4" else "#E7EAF2"))
                setOnClickListener { selectedGroupId = room.id; renderGroups(); renderGroupMessages() }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Leave ${room.name}?")
                        .setMessage("Remove this group and its key from this phone?")
                        .setNegativeButton("Keep", null)
                        .setPositiveButton("Leave") { _, _ ->
                            groupStore.remove(room.id)
                            groupHistory.remove(room.id)
                            if (selectedGroupId == room.id) selectedGroupId = null
                            renderGroups()
                        }
                        .show()
                    true
                }
            }
            card.addView(label(if (room.isPrivate) "▣" else "◉", 17f, "#5D61D8"), LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(label(room.name, 14f, "#202C47", bold = true))
            details.addView(label(if (room.isPrivate) "Private · invite key" else "Public · local Wi-Fi", 11f, "#858EA4"))
            card.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(label(if (selected) "●" else "›", 18f, "#6568DB"))
            groupList.addView(card, marginParams(bottom = 5))
        }
        renderGroupMessages()
    }

    private fun renderGroupMessages() {
        if (!::groupMessages.isInitialized) return
        groupMessages.removeAllViews()
        val room = selectedGroupId?.let(groupStore::find)
        activeGroupTitle.text = when {
            room == null -> "Select a group"
            room.isPrivate -> "${room.name}  ·  Private"
            else -> "${room.name}  ·  Public"
        }
        inviteGroupButton.isEnabled = room != null
        inviteGroupButton.alpha = if (room != null) 1f else 0.5f
        if (room == null) {
            groupMessages.addView(label("Groups send over local Wi-Fi to Nearby Chat peers. Public messages are readable on that network. Private group text is encrypted for people with the invite key.", 13f, "#7D879D").apply {
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(color("#FFFFFF"), 16, color("#E7EAF2"))
            })
            return
        }
        val history = groupHistory[room.id].orEmpty()
        if (history.isEmpty()) groupMessages.addView(label("This group is ready. Send a message or invite someone to join.", 13f, "#8A93A8").apply { setPadding(dp(10), dp(12), dp(10), dp(12)) })
        history.forEach { line -> addBubble(groupMessages, line.sender, line.text, outgoing = !line.incoming) }
    }

    private fun addGroupLine(roomId: String, line: GroupLine) {
        val history = groupHistory.getOrPut(roomId) { mutableListOf() }
        history.add(line)
        if (history.size > 200) history.removeAt(0)
    }

    private fun showCreateGroupDialog() {
        val name = EditText(this).apply { hint = "Group name"; setSingleLine(true); setText("Nearby group") }
        val privateCheck = CheckBox(this).apply { text = "Private group · encrypt group text with an invite key" }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        form.addView(name)
        form.addView(privateCheck)
        AlertDialog.Builder(this)
            .setTitle("Create a group")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val room = groupStore.create(name.text.toString(), privateCheck.isChecked)
                selectedGroupId = room.id
                showGroups(true)
                renderGroups()
                showInviteDialog(room)
            }
            .show()
    }

    private fun showJoinGroupDialog() {
        val code = EditText(this).apply { hint = "Paste a group invite"; minLines = 2; setTextIsSelectable(true) }
        AlertDialog.Builder(this)
            .setTitle("Join a group")
            .setMessage("Paste the invite code shared by a group member.")
            .setView(code)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Join") { _, _ ->
                val room = groupStore.join(code.text.toString())
                if (room == null) Toast.makeText(this, "That invite is invalid or conflicts with a saved group", Toast.LENGTH_LONG).show()
                else {
                    selectedGroupId = room.id
                    showGroups(true)
                    renderGroups()
                }
            }
            .show()
    }

    private fun showInviteDialog(room: GroupRoom) {
        val invite = groupStore.invite(room)
        val explanation = if (room.isPrivate) "Anyone with this invite key can read private group messages. Share it only with people you trust. Sender identities are not verified."
        else "This open group sends readable messages to Nearby Chat peers on the same local Wi-Fi network."
        AlertDialog.Builder(this)
            .setTitle("Invite to ${room.name}")
            .setMessage("$explanation\n\n$invite")
            .setNegativeButton("Close", null)
            .setPositiveButton("Copy invite") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Nearby Chat group invite", invite))
                Toast.makeText(this, "Invite copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun sendGroupMessage() {
        val room = selectedGroupId?.let(groupStore::find)
        if (room == null) {
            Toast.makeText(this, "Choose a group first", Toast.LENGTH_SHORT).show()
            return
        }
        val text = groupMessageInput.text.toString().trim()
        if (text.isEmpty()) return
        if (text.length > 24_000) {
            Toast.makeText(this, "Group messages are limited to 24,000 characters", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = runCatching { if (room.isPrivate) GroupMessageCipher.encrypt(room, text) else text }.getOrElse {
            Toast.makeText(this, "Could not prepare this group message", Toast.LENGTH_SHORT).show()
            return
        }
        lan?.sendGroupMessage(room.id, room.name, room.isPrivate, payload)
        addGroupLine(room.id, GroupLine("You", text, incoming = false))
        groupMessageInput.setText("")
        renderGroupMessages()
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
        addBubble(messages, "${if (message.incoming) message.sender else "You"} · ${message.via}", message.text, outgoing = !message.incoming)
    }

    private fun appendText(text: String) {
        addBubble(messages, "Nearby Chat", text, outgoing = false)
    }

    private fun addBubble(parent: LinearLayout, sender: String, text: String, outgoing: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (outgoing) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (outgoing) Gravity.END else Gravity.START
        }
        val metadata = label(sender, 10f, "#8790A5").apply { setPadding(dp(6), 0, dp(6), dp(3)) }
        column.addView(metadata)
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(color(if (outgoing) "#FFFFFF" else "#202C47"))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            maxWidth = dp(300)
            background = rounded(color(if (outgoing) "#5658DF" else "#FFFFFF"), 16, if (outgoing) null else color("#E7EAF2"))
        }
        column.addView(bubble)
        row.addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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

    private data class GroupLine(val sender: String, val text: String, val incoming: Boolean)
}
