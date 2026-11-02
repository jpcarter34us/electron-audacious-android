package io.github.jpcarter34us.audacious.util

import android.content.Context
import android.os.Build
import io.github.jpcarter34us.audacious.R
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The app's HTTP and WebSocket client. OkHttp 3.12 is the last version that
 * runs on Android 4.x; there, TLS 1.2 exists but is off by default (turned
 * on here), and Let's Encrypt's roots are missing before Android 7.1
 * (bundled in res/raw and trusted alongside the system's).
 */
object Http {
    lateinit var client: OkHttpClient
        private set

    fun init(context: Context) {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        try {
            val trust = trustManager(context)
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf(trust), null)
            val factory = if (Build.VERSION.SDK_INT < 21) Tls12SocketFactory(ssl.socketFactory) else ssl.socketFactory
            builder.sslSocketFactory(factory, trust)
        } catch (e: Exception) {
            android.util.Log.w("Http", "using the platform TLS defaults", e)
        }
        client = builder.build()
    }

    /** A client for long downloads (no read timeout between chunks of a stream). */
    val streaming: OkHttpClient by lazy { client.newBuilder().readTimeout(0, TimeUnit.SECONDS).build() }

    fun request(url: String, headers: Map<String, String> = emptyMap()): Request {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.header(k, v)
        return b.build()
    }

    private fun trustManager(context: Context): X509TrustManager {
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).let {
            it.init(null as KeyStore?)
            it.trustManagers.first { tm -> tm is X509TrustManager } as X509TrustManager
        }
        if (Build.VERSION.SDK_INT >= 25) return system
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        val cf = CertificateFactory.getInstance("X.509")
        for ((i, id) in listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2).withIndex()) {
            context.resources.openRawResource(id).use { store.setCertificateEntry("isrg$i", cf.generateCertificate(it)) }
        }
        val extra = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).let {
            it.init(store)
            it.trustManagers.first { tm -> tm is X509TrustManager } as X509TrustManager
        }
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    extra.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + extra.acceptedIssuers
        }
    }

    /** Turns on TLS 1.1 and 1.2 for sockets on Android 4.1-4.4. */
    private class Tls12SocketFactory(private val base: SSLSocketFactory) : SSLSocketFactory() {
        private fun patch(s: Socket): Socket {
            if (s is SSLSocket) {
                val wanted = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1")
                s.enabledProtocols = wanted.filter { it in s.supportedProtocols }.toTypedArray()
            }
            return s
        }

        override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites

        @Throws(IOException::class)
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean) = patch(base.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String, port: Int) = patch(base.createSocket(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) =
            patch(base.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress, port: Int) = patch(base.createSocket(host, port))
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) =
            patch(base.createSocket(address, port, localAddress, localPort))
    }
}
