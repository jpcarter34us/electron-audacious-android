package io.github.jpcarter34us.audacious.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lenient readers for server JSON (the iOS port's JSON helpers): servers
 * send ids as numbers or strings, booleans as numbers, and so on.
 */
object Json {
    fun str(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        is String -> v
        is Int, is Long -> v.toString()
        is Number -> {
            val d = v.toDouble()
            if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        }
        is Boolean -> v.toString()
        else -> null
    }

    fun int(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.toInt()
        else -> null
    }

    fun long(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong()
        else -> null
    }

    fun double(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun bool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.equals("true", true) || v == "1"
        else -> false
    }

    /** The JSON objects in an array (or a lone object), skipping others. */
    fun objects(v: Any?): List<JSONObject> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it) as? JSONObject }
        is JSONObject -> listOf(v)
        else -> emptyList()
    }

    fun strings(v: Any?): List<String>? = (v as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { str(a.opt(it)) } }

    fun parseObject(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (_: Exception) {
        null
    }

    fun array(items: Iterable<Any?>): JSONArray = JSONArray().also { a -> items.forEach { a.put(it) } }

    fun obj(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().also { o -> for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL) }
}

fun JSONObject.str(key: String) = Json.str(opt(key))
fun JSONObject.int(key: String) = Json.int(opt(key))
fun JSONObject.long(key: String) = Json.long(opt(key))
fun JSONObject.double(key: String) = Json.double(opt(key))
fun JSONObject.bool(key: String) = Json.bool(opt(key))
fun JSONObject.obj(key: String): JSONObject? = opt(key) as? JSONObject
fun JSONObject.objects(key: String) = Json.objects(opt(key))
fun JSONObject.strings(key: String) = Json.strings(opt(key))
