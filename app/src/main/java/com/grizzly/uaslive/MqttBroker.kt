package com.grizzly.uaslive

import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Minimal MQTT broker (3.1.1 and 5) for one job: DJI Pilot 2 connects to it as its "third-party cloud".
 * Supports CONNECT, PUBLISH (QoS 0/1/2 in), SUBSCRIBE, UNSUBSCRIBE, PINGREQ, DISCONNECT, and server-side publish
 * to subscribed clients (used for the *_reply handshakes Pilot 2 waits for).
 */
class MqttBroker(
    private val port: Int,
    private val user: String,
    private val pass: String,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
    private val log: (String) -> Unit,
) {
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile var running = false; private set
    val connectedClients get() = clients.count { it.authed }

    inner class Client(val sock: Socket) {
        val out: OutputStream = sock.getOutputStream()
        val subs = CopyOnWriteArrayList<String>()
        var v5 = false
        var authed = false
        var id = ""
        fun send(b: ByteArray) = synchronized(out) { out.write(b); out.flush() }
    }

    fun start(bindAll: Boolean = false) {
        if (running) return
        server = ServerSocket(port, 8, if (bindAll) null else InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        running = true
        thread(name = "mqtt-accept", isDaemon = true) {
            while (running) {
                val s = try { server!!.accept() } catch (e: Exception) { break }
                val c = Client(s)
                clients += c
                thread(name = "mqtt-client", isDaemon = true) { serve(c) }
            }
        }
        log("MQTT broker listening on port $port")
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        clients.forEach { runCatching { it.sock.close() } }
        clients.clear()
    }

    /** Publish from the server to every client subscribed to a matching filter (QoS 0). */
    fun publish(topic: String, payload: ByteArray) {
        for (c in clients) if (c.authed && c.subs.any { matches(it, topic) }) {
            runCatching { c.send(publishPacket(topic, payload, c.v5)) }
        }
    }

    private fun serve(c: Client) {
        val inp = DataInputStream(c.sock.getInputStream().buffered())
        try {
            while (running) {
                val h = inp.readUnsignedByte()
                val type = h shr 4; val flags = h and 0x0F
                val len = readVarInt(inp)
                val body = ByteArray(len); inp.readFully(body)
                when (type) {
                    1 -> connect(c, body)
                    3 -> incomingPublish(c, flags, body)
                    6 -> c.send(byteArrayOf(0x70, 0x02, body[0], body[1]))              // PUBREL -> PUBCOMP
                    8 -> subscribe(c, body)
                    10 -> unsubscribe(c, body)
                    12 -> c.send(byteArrayOf(0xD0.toByte(), 0x00))                       // PINGREQ -> PINGRESP
                    14 -> break                                                          // DISCONNECT
                    else -> {}
                }
            }
        } catch (_: EOFException) {
        } catch (e: Exception) {
            if (running) log("MQTT client error: ${e.message}")
        } finally {
            runCatching { c.sock.close() }
            clients.remove(c)
            if (c.authed) log("Pilot 2 disconnected (${c.id})")
        }
    }

    private fun connect(c: Client, b: ByteArray) {
        var p = 0
        val protoLen = u16(b, p); p += 2 + protoLen
        val level = b[p].toInt(); p += 1
        c.v5 = level >= 5
        val cflags = b[p].toInt() and 0xFF; p += 1
        p += 2                                                                           // keep-alive
        if (c.v5) { val (pl, n) = varIntAt(b, p); p += n + pl }                        // CONNECT properties
        val (cid, n1) = str(b, p); p += n1; c.id = cid
        if (cflags and 0x04 != 0) {                                                      // will
            if (c.v5) { val (pl, n) = varIntAt(b, p); p += n + pl }
            p += 2 + u16(b, p); p += 2 + u16(b, p)
        }
        var u = ""; var pw = ""
        if (cflags and 0x80 != 0) { val (s, n) = str(b, p); u = s; p += n }
        if (cflags and 0x40 != 0) { val (s, n) = str(b, p); pw = s; p += n }
        val ok = user.isEmpty() || (u == user && pw == pass)
        val rc: Byte = if (ok) 0 else if (c.v5) 0x86.toByte() else 0x04
        c.send(if (c.v5) byteArrayOf(0x20, 0x03, 0x00, rc, 0x00) else byteArrayOf(0x20, 0x02, 0x00, rc))
        if (!ok) { log("MQTT login refused for '$u'"); c.sock.close(); return }
        c.authed = true
        log("Pilot 2 connected (client $cid, MQTT ${if (c.v5) "5" else "3.1.1"})")
    }

    private fun incomingPublish(c: Client, flags: Int, b: ByteArray) {
        val qos = (flags shr 1) and 3
        var p = 0
        val (topic, n) = str(b, p); p += n
        var pid = -1
        if (qos > 0) { pid = u16(b, p); p += 2 }
        if (c.v5) { val (pl, m) = varIntAt(b, p); p += m + pl }
        val payload = b.copyOfRange(p, b.size)
        when (qos) {
            1 -> c.send(byteArrayOf(0x40, 0x02, (pid shr 8).toByte(), pid.toByte()))     // PUBACK
            2 -> c.send(byteArrayOf(0x50, 0x02, (pid shr 8).toByte(), pid.toByte()))     // PUBREC
        }
        onMessage(topic, payload)
    }

    private fun subscribe(c: Client, b: ByteArray) {
        var p = 0
        val pid = u16(b, p); p += 2
        if (c.v5) { val (pl, n) = varIntAt(b, p); p += n + pl }
        val granted = ArrayList<Byte>()
        while (p < b.size) {
            val (f, n) = str(b, p); p += n
            val opts = b[p].toInt(); p += 1
            c.subs += f
            granted += minOf(opts and 3, 1).toByte()
        }
        val head = byteArrayOf((pid shr 8).toByte(), pid.toByte()) + (if (c.v5) byteArrayOf(0) else byteArrayOf())
        val bodyOut = head + granted.toByteArray()
        c.send(byteArrayOf(0x90.toByte()) + varInt(bodyOut.size) + bodyOut)
    }

    private fun unsubscribe(c: Client, b: ByteArray) {
        var p = 0
        val pid = u16(b, p); p += 2
        if (c.v5) { val (pl, n) = varIntAt(b, p); p += n + pl }
        var count = 0
        while (p < b.size) { val (f, n) = str(b, p); p += n; c.subs.remove(f); count++ }
        val bodyOut = byteArrayOf((pid shr 8).toByte(), pid.toByte()) +
            (if (c.v5) byteArrayOf(0) + ByteArray(count) else byteArrayOf())
        c.send(byteArrayOf(0xB0.toByte()) + varInt(bodyOut.size) + bodyOut)
    }

    companion object {
        fun matches(filter: String, topic: String): Boolean {
            val f = filter.split('/'); val t = topic.split('/')
            for (i in f.indices) {
                if (f[i] == "#") return true
                if (i >= t.size) return false
                if (f[i] != "+" && f[i] != t[i]) return false
            }
            return f.size == t.size
        }

        fun publishPacket(topic: String, payload: ByteArray, v5: Boolean): ByteArray {
            val tb = topic.toByteArray()
            val body = byteArrayOf((tb.size shr 8).toByte(), tb.size.toByte()) + tb +
                (if (v5) byteArrayOf(0) else byteArrayOf()) + payload
            return byteArrayOf(0x30) + varInt(body.size) + body
        }

        fun varInt(v: Int): ByteArray {
            val out = ArrayList<Byte>(); var x = v
            do { var d = x % 128; x /= 128; if (x > 0) d = d or 0x80; out += d.toByte() } while (x > 0)
            return out.toByteArray()
        }

        private fun readVarInt(i: DataInputStream): Int {
            var mul = 1; var v = 0
            while (true) { val d = i.readUnsignedByte(); v += (d and 127) * mul; if (d and 128 == 0) return v; mul *= 128 }
        }

        private fun varIntAt(b: ByteArray, start: Int): Pair<Int, Int> {
            var mul = 1; var v = 0; var p = start
            while (true) { val d = b[p].toInt() and 0xFF; p++; v += (d and 127) * mul; if (d and 128 == 0) return v to (p - start); mul *= 128 }
        }

        private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        private fun str(b: ByteArray, p: Int): Pair<String, Int> { val l = u16(b, p); return String(b, p + 2, l) to (2 + l) }
    }
}
