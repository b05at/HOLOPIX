package at.b05.holopix.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimaler MQTT-3.1.1-Client (nur Verbinden, Veröffentlichen mit QoS 0, Trennen) – reicht für Statuswerte
 * und kommt ohne zusätzliche Bibliothek aus. Unverschlüsselt (Port 1883), wie bei Mosquitto im Heimnetz üblich.
 */
class MqttLite(private val host: String, private val port: Int, private val user: String, private val password: String, private val clientId: String) {

    class Message(val topic: String, val payload: String, val retain: Boolean = true)

    /** Verbindet, veröffentlicht alle Nachrichten und trennt wieder. Wirft eine Exception mit verständlichem Text. */
    fun publish(messages: List<Message>) {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 8000)
            s.soTimeout = 8000
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())
            out.write(connectPacket()); out.flush()
            // CONNACK: 0x20 0x02 <flags> <code>
            val type = inp.readUnsignedByte()
            inp.readUnsignedByte()
            inp.readUnsignedByte()
            val code = inp.readUnsignedByte()
            if (type != 0x20) throw java.io.IOException("Unerwartete Antwort vom Broker")
            if (code != 0) throw java.io.IOException(
                when (code) {
                    1 -> "Broker: Protokollversion nicht unterstützt"
                    2 -> "Broker: Client-ID abgelehnt"
                    3 -> "Broker nicht verfügbar"
                    4 -> "Benutzer oder Passwort falsch"
                    5 -> "Nicht berechtigt"
                    else -> "Broker lehnt Verbindung ab (Code $code)"
                }
            )
            messages.forEach { out.write(publishPacket(it)) }
            out.write(byteArrayOf(0xE0.toByte(), 0)) // DISCONNECT
            out.flush()
        }
    }

    private fun connectPacket(): ByteArray {
        val body = ByteArrayOutputStream()
        body.str("MQTT")
        body.write(4) // Protokoll 3.1.1
        var flags = 0x02 // Clean Session
        if (user.isNotBlank()) flags = flags or 0x80
        if (user.isNotBlank() && password.isNotEmpty()) flags = flags or 0x40
        body.write(flags)
        body.write(0); body.write(30) // Keep-Alive 30 s
        body.str(clientId)
        if (user.isNotBlank()) body.str(user)
        if (user.isNotBlank() && password.isNotEmpty()) body.str(password)
        return packet(0x10, body.toByteArray())
    }

    private fun publishPacket(m: Message): ByteArray {
        val body = ByteArrayOutputStream()
        body.str(m.topic)
        body.write(m.payload.toByteArray(Charsets.UTF_8))
        return packet(0x30 or (if (m.retain) 1 else 0), body.toByteArray())
    }

    private fun packet(header: Int, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(header)
        var len = body.size
        do {
            var b = len % 128; len /= 128
            if (len > 0) b = b or 0x80
            o.write(b)
        } while (len > 0)
        o.write(body)
        return o.toByteArray()
    }

    private fun OutputStream.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        write(b.size shr 8); write(b.size and 0xFF); write(b)
    }
}
