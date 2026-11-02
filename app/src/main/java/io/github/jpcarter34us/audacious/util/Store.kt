package io.github.jpcarter34us.audacious.util

import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Settings (the iOS port's UserDefaults). */
object Prefs {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    fun string(key: String, default: String = ""): String = prefs.getString(key, default) ?: default
    fun stringOrNull(key: String): String? = prefs.getString(key, null)
    fun bool(key: String, default: Boolean = false) = prefs.getBoolean(key, default)
    fun int(key: String, default: Int = 0) = prefs.getInt(key, default)
    fun float(key: String, default: Float) = prefs.getFloat(key, default)
    fun has(key: String) = prefs.contains(key)

    fun set(key: String, value: Any?) {
        val e = prefs.edit()
        when (value) {
            null -> e.remove(key)
            is String -> e.putString(key, value)
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            is Float -> e.putFloat(key, value)
            is Double -> e.putFloat(key, value.toFloat())
            is Long -> e.putLong(key, value)
            else -> e.putString(key, value.toString())
        }
        e.apply()
    }

    fun strings(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return Json.strings(try {
            org.json.JSONArray(raw)
        } catch (_: Exception) {
            null
        }) ?: emptyList()
    }

    fun setStrings(key: String, values: List<String>) = set(key, Json.array(values).toString())
}

/**
 * Passwords, API keys and tokens (the iOS port's Keychain). On Android 6
 * and later each value is encrypted with an AES key in the Android
 * Keystore; before that the app's private storage is all there is.
 */
object Secrets {
    private lateinit var prefs: SharedPreferences
    private const val ALIAS = "audacious-secrets"

    fun init(context: Context) {
        prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (!stored.startsWith("k1:")) return stored
        if (Build.VERSION.SDK_INT < 23) return null
        return try {
            decrypt(stored.substring(3))
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun set(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val stored = if (Build.VERSION.SDK_INT >= 23) {
            try {
                "k1:" + encrypt(value)
            } catch (e: Exception) {
                value
            }
        } else value
        prefs.edit().putString(key, stored).apply()
    }

    @TargetApi(23)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    @TargetApi(23)
    private fun encrypt(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    @TargetApi(23)
    private fun decrypt(text: String): String {
        val raw = Base64.decode(text, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }
}
