package io.github.jpcarter34us.audacious.library

import io.github.jpcarter34us.audacious.util.UserError

/**
 * DMAP, the binary format of DAAP (iTunes sharing) responses, ported from
 * ElectronPlayer's src/main/dmap.ts by way of the iOS port: each element
 * is a 4-character tag, a 32-bit big-endian length, and the value. The
 * format does not say which tags are containers, so they are listed here;
 * everything else is a leaf, read as the type the caller expects.
 */
class DmapNode(val tag: String, private val data: ByteArray, private val from: Int, private val to: Int,
               val children: List<DmapNode>) {
    companion object {
        val containers = setOf(
            "msrv", "mccr", "mdcl", "mlog", "mupd", "avdb", "adbs", "aply", "apso", "mlcl", "mlit",
            "mshl", "mlsh", "abro", "abar", "abal", "abcp", "abgn", "agal", "agar", "arsv", "arif",
            "prsv", "msml", "casp", "cmst", "mdbk", "ated",
        )

        /** Parses a response body; its top element (msrv, mlog, adbs, ...) is returned. */
        fun parse(bytes: ByteArray): DmapNode = parseList(bytes, 0, bytes.size, 0).firstOrNull() ?: throw UserError("Empty DAAP response")

        private fun parseList(b: ByteArray, start: Int, end: Int, depth: Int): List<DmapNode> {
            if (depth > 16) throw UserError("DMAP nesting too deep")
            val nodes = ArrayList<DmapNode>()
            var pos = start
            while (pos + 8 <= end) {
                val tag = String(b, pos, 4, Charsets.ISO_8859_1)
                val length = (b[pos + 4].toInt() and 0xFF shl 24) or (b[pos + 5].toInt() and 0xFF shl 16) or
                    (b[pos + 6].toInt() and 0xFF shl 8) or (b[pos + 7].toInt() and 0xFF)
                val from = pos + 8
                val to = from + length
                if (length < 0 || to > end) throw UserError("Truncated DMAP element $tag")
                nodes.add(if (tag in containers) DmapNode(tag, b, from, from, parseList(b, from, to, depth + 1))
                else DmapNode(tag, b, from, to, emptyList()))
                pos = to
            }
            return nodes
        }
    }

    /** First direct child with this tag. */
    fun get(tag: String) = children.firstOrNull { it.tag == tag }

    /** Every direct child with this tag. */
    fun all(tag: String) = children.filter { it.tag == tag }

    /** Follows a path of tags: node.at("mlcl", "mlit"). */
    fun at(vararg tags: String): DmapNode? {
        var node: DmapNode? = this
        for (t in tags) node = node?.get(t)
        return node
    }

    /** The leaf as an unsigned big-endian integer (1, 2, 4 or 8 bytes). */
    val int: Long
        get() {
            val n = to - from
            if (n !in listOf(1, 2, 4, 8)) return 0
            var v = 0L
            for (i in from until to) v = (v shl 8) or (data[i].toLong() and 0xFF)
            return v
        }

    val string: String get() = String(data, from, to - from, Charsets.UTF_8)

    fun num(tag: String): Int? = get(tag)?.int?.toInt()
    fun text(tag: String): String? = get(tag)?.string
}
