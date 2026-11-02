package io.github.jpcarter34us.audacious.sendspin

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the Sendspin identity, PSKs and pairing failure count persist: the
 * app's secret store on the device, memory in tests. Same keys as
 * sendspin-js's localStorage (and the iOS port's Keychain items).
 */
interface SendspinStorage {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

class MemorySendspinStorage : SendspinStorage {
    private val values = HashMap<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** The client's long-term X25519 key; its public key (base64url) is the
 *  client_id the server pins. */
class SendspinIdentity(val keyPair: KeyPair) {
    val clientID: String get() = Base64URL.encode(keyPair.publicKey)

    companion object {
        private const val KEY = "sendspin-identity-sk"

        fun loadOrCreate(storage: SendspinStorage): SendspinIdentity {
            val stored = storage.get(KEY)?.let { Base64URL.decode(it) }
            if (stored != null && stored.size == 32) {
                try {
                    return SendspinIdentity(KeyPair(stored))
                } catch (_: SendspinCryptoException) {
                }
            }
            val fresh = KeyPair.generate()
            storage.set(KEY, Base64URL.encode(fresh.privateKey))
            return SendspinIdentity(fresh)
        }
    }
}

/** psk_id = base64url(SHA-256("sendspin-psk-id-v1" || PSK)). */
fun sendspinPskID(psk: Bytes): String = Base64URL.encode(sha256(utf8("sendspin-psk-id-v1") + psk))

/** SHA-256("sendspin-sentinel-psk-v1"): the PSK every unpaired connection uses. */
val sendspinSentinelPSK: Bytes = sha256(utf8("sendspin-sentinel-psk-v1"))

/**
 * The PSKs a server may name in Noise message 1: the Sentinel (unpaired),
 * this client's Pairing PSK (typed into the server once), and long-term
 * PSKs minted by pairing, each pinned to one server.
 */
class SendspinPskStore(private val storage: SendspinStorage) {
    enum class Category(val wire: String) { SENTINEL("sentinel"), PAIRING("pairing"), LONG_TERM("long_term") }

    data class Entry(val psk: Bytes, val id: String, val category: Category, val serverID: String? = null)

    private val entries = LinkedHashMap<String, Entry>()

    companion object {
        private const val LONG_TERM_KEY = "sendspin-psks"
        private const val PAIRING_KEY = "sendspin-pairing-psk"
    }

    init {
        add(Entry(sendspinSentinelPSK, sendspinPskID(sendspinSentinelPSK), Category.SENTINEL))
        loadPersisted()
    }

    private fun add(entry: Entry) {
        entries[entry.id] = entry
    }

    @Synchronized
    private fun loadPersisted() {
        storage.get(LONG_TERM_KEY)?.let { raw ->
            try {
                val records = JSONArray(raw)
                for (i in 0 until records.length()) {
                    val r = records.optJSONObject(i) ?: continue
                    val psk = Base64URL.decode(r.optString("psk", "")) ?: continue
                    if (psk.isEmpty()) continue
                    add(Entry(psk, sendspinPskID(psk), Category.LONG_TERM, r.optString("serverId", "").ifEmpty { null }))
                }
            } catch (_: Exception) {
                storage.set(LONG_TERM_KEY, "[]")
            }
        }
        storage.get(PAIRING_KEY)?.takeIf { it.isNotEmpty() }?.let { raw ->
            val psk = Base64URL.decode(raw)
            if (psk != null) add(Entry(psk, sendspinPskID(psk), Category.PAIRING)) else storage.set(PAIRING_KEY, null)
        }
    }

    private fun persistLongTerm() {
        val records = JSONArray()
        for (e in entries.values) {
            if (e.category != Category.LONG_TERM) continue
            val r = JSONObject().put("psk", Base64URL.encode(e.psk))
            e.serverID?.let { r.put("serverId", it) }
            records.put(r)
        }
        storage.set(LONG_TERM_KEY, records.toString())
    }

    @Synchronized
    fun lookup(id: String): Entry? = entries[id]

    @Synchronized
    fun addLongTerm(psk: Bytes, serverID: String?): Entry {
        val entry = Entry(psk, sendspinPskID(psk), Category.LONG_TERM, serverID)
        add(entry)
        persistLongTerm()
        return entry
    }

    /** Removes a long-term PSK, unless it is a shared record (no server). */
    @Synchronized
    fun remove(id: String) {
        val e = entries[id] ?: return
        if (e.category != Category.LONG_TERM || e.serverID == null) return
        entries.remove(id)
        persistLongTerm()
    }

    /** Forgets every long-term PSK (unpairs from all servers). */
    @Synchronized
    fun removeAllLongTerm() {
        entries.values.removeAll { it.category == Category.LONG_TERM }
        persistLongTerm()
    }

    @get:Synchronized
    val pairedServers: List<String>
        get() = entries.values.filter { it.category == Category.LONG_TERM }.mapNotNull { it.serverID }.sorted()

    @Synchronized
    fun pairingPSK(): Bytes = entries.values.firstOrNull { it.category == Category.PAIRING }?.psk ?: setPairingPSK(randomBytes(32))

    @Synchronized
    fun rotatePairingPSK(): Bytes {
        entries.values.removeAll { it.category == Category.PAIRING }
        return setPairingPSK(randomBytes(32))
    }

    private fun setPairingPSK(psk: Bytes): Bytes {
        add(Entry(psk, sendspinPskID(psk), Category.PAIRING))
        storage.set(PAIRING_KEY, Base64URL.encode(psk))
        return psk
    }
}

/** "SP:0" + base32(client key || pairing PSK) with "2" written as "9":
 *  what the operator types or scans into the server. */
fun sendspinPairingToken(clientKey: Bytes, pairingPSK: Bytes): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var bits = 0
    var value = 0
    val out = StringBuilder()
    for (byte in clientKey + pairingPSK) {
        value = (value shl 8) or (byte.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
            out.append(alphabet[(value shr (bits - 5)) and 31])
            bits -= 5
        }
        value = value and ((1 shl bits) - 1)
    }
    if (bits > 0) out.append(alphabet[(value shl (5 - bits)) and 31])
    return "SP:0" + out.toString().replace('2', '9')
}
