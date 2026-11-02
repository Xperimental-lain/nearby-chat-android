package dev.nearbychat.transport

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class GroupRoom(
    val id: String,
    val name: String,
    val isPrivate: Boolean,
    val key: String? = null,
)

class GroupRoomStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("group_rooms", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<GroupRoom> = runCatching {
        val array = JSONArray(preferences.getString("rooms", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = item.optString("name").take(40).ifBlank { "Nearby group" }
            val privateRoom = item.optBoolean("private")
            val key = item.optString("key").takeIf(String::isNotBlank)
            if (privateRoom && key == null) null else GroupRoom(id, name, privateRoom, key)
        }
    }.getOrDefault(emptyList())

    fun find(id: String): GroupRoom? = all().firstOrNull { it.id == id }

    @Synchronized
    fun create(name: String, isPrivate: Boolean): GroupRoom {
        val cleanName = name.trim().take(40).ifBlank { "Nearby group" }
        val key = if (isPrivate) ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.encodeToString(it, Base64.NO_WRAP) } else null
        return GroupRoom(UUID.randomUUID().toString(), cleanName, isPrivate, key).also(::put)
    }

    @Synchronized
    fun join(invite: String): GroupRoom? {
        val normalizedInvite = invite.trim()
        if (normalizedInvite.length > 512) return null
        val fields = normalizedInvite.split('|')
        if (fields.size !in 4..5 || fields[0] != "NCHAT1") return null
        val isPrivate = when (fields[1]) { "PUBLIC" -> false; "PRIVATE" -> true; else -> return null }
        val id = fields[2].takeIf { it.matches(Regex("[A-Za-z0-9-]{8,80}")) } ?: return null
        val name = runCatching { String(Base64.decode(fields[3], Base64.DEFAULT), Charsets.UTF_8).take(40).ifBlank { "Nearby group" } }.getOrNull() ?: return null
        val key = if (isPrivate) {
            if (fields.size != 5) return null
            fields[4].takeIf { it.length <= 64 && runCatching { Base64.decode(it, Base64.DEFAULT).size == 32 }.getOrDefault(false) } ?: return null
        } else {
            if (fields.size != 4) return null
            null
        }
        find(id)?.let { existing ->
            return if (existing.isPrivate == isPrivate && existing.key == key) existing else null
        }
        return GroupRoom(id, name, isPrivate, key).also(::put)
    }

    fun invite(room: GroupRoom): String {
        val safeName = Base64.encodeToString(room.name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return if (room.isPrivate) "NCHAT1|PRIVATE|${room.id}|$safeName|${room.key}"
        else "NCHAT1|PUBLIC|${room.id}|$safeName"
    }

    fun acceptPublicMessage(id: String, name: String): GroupRoom? {
        if (!id.matches(Regex("[A-Za-z0-9-]{8,80}"))) return null
        find(id)?.let { return if (it.isPrivate) null else it }
        if (all().size >= 100) return null
        return GroupRoom(id, name.trim().take(40).ifBlank { "Nearby group" }, false).also(::put)
    }

    @Synchronized
    fun remove(id: String) = persist(all().filterNot { it.id == id })

    @Synchronized
    private fun put(room: GroupRoom) = persist(all().filterNot { it.id == room.id } + room)

    private fun persist(rooms: List<GroupRoom>) {
        val array = JSONArray()
        rooms.forEach { room ->
            array.put(JSONObject().put("id", room.id).put("name", room.name).put("private", room.isPrivate).put("key", room.key))
        }
        preferences.edit().putString("rooms", array.toString()).apply()
    }
}

object GroupMessageCipher {
    fun encrypt(room: GroupRoom, text: String): String {
        val key = room.key ?: error("Group invite key is missing")
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.decode(key, Base64.NO_WRAP), "AES"), GCMParameterSpec(128, nonce))
        val sealed = nonce + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun decrypt(room: GroupRoom, payload: String): String {
        val key = room.key ?: error("Group invite key is missing")
        val sealed = Base64.decode(payload, Base64.DEFAULT)
        require(sealed.size >= 28) { "Encrypted message is incomplete" }
        val nonce = sealed.copyOfRange(0, 12)
        val ciphertext = sealed.copyOfRange(12, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.decode(key, Base64.NO_WRAP), "AES"), GCMParameterSpec(128, nonce))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
