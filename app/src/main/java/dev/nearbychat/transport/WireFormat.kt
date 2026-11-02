package dev.nearbychat.transport

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object WireFormat {
    private const val MAX_LINE_BYTES = 1024 * 1024
    const val MAX_FILE_BYTES = 100L * 1024 * 1024

    sealed interface Frame {
        data class Hello(val id: String, val name: String) : Frame
        data class Text(val id: String, val name: String, val text: String) : Frame
        data class GroupText(
            val id: String,
            val name: String,
            val groupId: String,
            val groupName: String,
            val encrypted: Boolean,
            val payload: String,
        ) : Frame
        data class File(val id: String, val name: String, val fileName: String, val mime: String, val size: Long) : Frame
        data class Control(val action: String, val value: String) : Frame
    }

    fun encodeHello(id: String, name: String) = line("NCHAT2|HELLO|${b64(id)}|${b64(name)}")
    fun encodeText(id: String, name: String, text: String) = line("NCHAT2|TEXT|${b64(id)}|${b64(name)}|${b64(text)}")
    fun encodeGroupText(id: String, name: String, groupId: String, groupName: String, encrypted: Boolean, payload: String) =
        line("NCHAT2|GROUP|${b64(id)}|${b64(name)}|${b64(groupId)}|${b64(groupName)}|${if (encrypted) 1 else 0}|${b64(payload)}")
    fun encodeFileHeader(id: String, name: String, fileName: String, mime: String, size: Long) =
        line("NCHAT2|FILE|${b64(id)}|${b64(name)}|${b64(fileName)}|${b64(mime)}|$size")
    fun encodeControl(action: String, value: String) = line("NCHAT2|$action|${b64(value)}")

    fun decode(line: String): Frame? {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return null
        val fields = line.split('|')
        if (fields.size < 3 || fields[0] != "NCHAT2") return null
        return runCatching {
            when (fields[1]) {
                "HELLO" -> if (fields.size == 4) Frame.Hello(unb64(fields[2]), unb64(fields[3])) else null
                "TEXT" -> if (fields.size == 5) Frame.Text(unb64(fields[2]), unb64(fields[3]), unb64(fields[4])) else null
                "GROUP" -> if (fields.size == 8 && fields[6] in setOf("0", "1")) {
                    Frame.GroupText(unb64(fields[2]), unb64(fields[3]), unb64(fields[4]), unb64(fields[5]), fields[6] == "1", unb64(fields[7]))
                } else null
                "FILE" -> {
                    if (fields.size != 7) return null
                    val size = fields[6].toLong()
                    if (size !in 0..MAX_FILE_BYTES) return null
                    Frame.File(unb64(fields[2]), unb64(fields[3]), unb64(fields[4]), unb64(fields[5]), size)
                }
                "READY", "DONE" -> if (fields.size == 3) Frame.Control(fields[1], unb64(fields[2])) else null
                else -> null
            }
        }.getOrNull()
    }

    fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (line.size() <= MAX_LINE_BYTES) {
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else line.toString(Charsets.UTF_8.name())
            if (byte == '\n'.code) return line.toString(Charsets.UTF_8.name())
            line.write(byte)
        }
        return null
    }

    private fun line(value: String) = (value + "\n").toByteArray(Charsets.UTF_8)
    private fun b64(value: String) = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun unb64(value: String) = String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
}
