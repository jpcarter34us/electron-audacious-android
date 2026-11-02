package io.github.jpcarter34us.audacious.library

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * A small mDNS/DNS-SD browser (the iOS port's Bonjour.swift): asks for
 * PTR records of a service type and reads the SRV, TXT and A records that
 * come back. Android's NsdManager exists from 4.1 but gives TXT records
 * only from 5.0, and DAAP needs them (share name, password flag).
 */
object Mdns {
    class Service(val name: String, val host: String, val port: Int, val txt: Map<String, String>)

    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")

    /** Browses `type` (e.g. "_daap._tcp") for about `seconds`. Blocks. */
    fun browse(context: Context, type: String, seconds: Double = 3.0): List<Service> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("mdns").apply { setReferenceCounted(false) }
        lock.acquire()
        val fqdn = "$type.local"
        val ptrs = LinkedHashSet<String>()
        val srv = HashMap<String, Pair<String, Int>>()
        val txt = HashMap<String, Map<String, String>>()
        val addresses = HashMap<String, String>()
        try {
            // DatagramSocket is Closeable only from Android 4.4, so no use {}.
            val socket = MulticastSocket(null as java.net.SocketAddress?)
            try {
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(5353))
                socket.joinGroup(GROUP)
                socket.soTimeout = 250
                val query = query(fqdn)
                val deadline = System.currentTimeMillis() + (seconds * 1000).toLong()
                var nextQuery = 0L
                val buf = ByteArray(9000)
                while (System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() >= nextQuery) {
                        socket.send(DatagramPacket(query, query.size, GROUP, 5353))
                        nextQuery = System.currentTimeMillis() + 1000
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    try {
                        parse(buf.copyOf(packet.length), fqdn, ptrs, srv, txt, addresses, packet.address.hostAddress)
                    } catch (_: Exception) {
                    }
                }
                socket.leaveGroup(GROUP)
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            android.util.Log.w("mdns", "browse failed", e)
        } finally {
            lock.release()
        }
        return ptrs.mapNotNull { instance ->
            val (target, port) = srv[instance] ?: return@mapNotNull null
            val host = addresses[target.lowercase()] ?: addresses["@$instance"] ?: return@mapNotNull null
            Service(instance.removeSuffix(".$fqdn"), host, port, txt[instance] ?: emptyMap())
        }
    }

    private fun query(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            val b = label.toByteArray()
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        out.write(byteArrayOf(0, 12, 0, 1)) // PTR, IN
        return out.toByteArray()
    }

    private fun readName(b: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var pos = start
        var end = -1
        var jumps = 0
        while (true) {
            val len = b[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (end < 0) end = pos + 2
                pos = ((len and 0x3F) shl 8) or (b[pos + 1].toInt() and 0xFF)
                if (++jumps > 20) break
                continue
            }
            labels.add(String(b, pos + 1, len, Charsets.UTF_8))
            pos += 1 + len
        }
        return labels.joinToString(".") to (if (end >= 0) end else pos)
    }

    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF shl 8) or (b[p + 1].toInt() and 0xFF)

    private fun parse(b: ByteArray, fqdn: String, ptrs: MutableSet<String>, srv: MutableMap<String, Pair<String, Int>>,
                      txt: MutableMap<String, Map<String, String>>, addresses: MutableMap<String, String>, sender: String) {
        val qd = u16(b, 4)
        val records = u16(b, 6) + u16(b, 8) + u16(b, 10)
        var pos = 12
        repeat(qd) {
            pos = readName(b, pos).second + 4
        }
        repeat(records) {
            val (name, p) = readName(b, pos)
            val type = u16(b, p)
            val length = u16(b, p + 8)
            val data = p + 10
            when (type) {
                12 -> if (name.equals(fqdn, true)) {
                    val instance = readName(b, data).first
                    ptrs.add(instance)
                    if ("@$instance" !in addresses) addresses["@$instance"] = sender
                }
                33 -> srv[name] = readName(b, data + 6).first to u16(b, data + 4)
                16 -> {
                    val map = HashMap<String, String>()
                    var q = data
                    while (q < data + length) {
                        val l = b[q].toInt() and 0xFF
                        val entry = String(b, q + 1, l, Charsets.UTF_8)
                        val eq = entry.indexOf('=')
                        if (eq > 0) map[entry.substring(0, eq)] = entry.substring(eq + 1) else if (entry.isNotEmpty()) map[entry] = ""
                        q += 1 + l
                    }
                    txt[name] = map
                }
                1 -> if (length == 4) addresses[name.lowercase()] =
                    (0 until 4).joinToString(".") { (b[data + it].toInt() and 0xFF).toString() }
            }
            pos = data + length
        }
    }
}
