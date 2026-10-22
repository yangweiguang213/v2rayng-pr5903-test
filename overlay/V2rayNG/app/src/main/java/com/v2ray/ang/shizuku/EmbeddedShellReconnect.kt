package com.v2ray.ang.shizuku

import android.content.Context
import android.os.IBinder
import android.util.Log
import com.v2ray.ang.BuildConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Reattach to the existing shell process without an ADB connection or a port scan. */
internal object EmbeddedShellReconnect {
    private const val KEY = "reconnect_secret"
    private const val PROTOCOL = "v2rayng-reconnect-3"
    private val address get() = InetAddress.getByName("127.0.0.1")
    private val port get() = 42000 + (BuildConfig.APPLICATION_ID.hashCode() and 0x7fffffff) % 10000
    private fun nonce() = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private fun proof(key: String, message: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun matches(actual: String, expected: String) =
        MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    @Synchronized
    fun secret(context: Context): String {
        val prefs = context.getSharedPreferences("embedded_adb", Context.MODE_PRIVATE)
        return prefs.getString(KEY, null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also {
                check(prefs.edit().putString(KEY, it).commit()) { "Cannot save service reconnect key" }
            }
    }

    @Synchronized
    fun reconnect(context: Context): Boolean {
        if (EmbeddedShellBinderProvider.binder() != null) return true
        val key = context.getSharedPreferences("embedded_adb", Context.MODE_PRIVATE).getString(KEY, null)
            ?: return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), 1000)
                socket.soTimeout = 3500
                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())
                val clientNonce = nonce()
                output.apply { writeUTF(PROTOCOL); writeUTF(clientNonce); flush() }
                check(matches(input.readUTF(), proof(key, "server:$clientNonce"))) { "Invalid server proof" }
                val serverNonce = input.readUTF()
                check(serverNonce.length == 64)
                val token = EmbeddedShellBinderProvider.arm()
                output.apply {
                    writeUTF(token)
                    writeUTF(proof(key, "client:$serverNonce:$clientNonce:$token"))
                    flush()
                }
                input.readBoolean() && EmbeddedShellBinderProvider.binder() != null
            }
        }.onFailure { Log.d("EmbeddedShellReconnect", "No reusable connection: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
    }

    // Bind before creating a service: a second launch cannot create another routing owner.
    fun reserve(): ServerSocket = ServerSocket().apply {
        reuseAddress = false
        bind(InetSocketAddress(address, port), 4)
    }

    fun serve(server: ServerSocket, service: IBinder, key: String) {
        require(key.length == 64)
        Thread({
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                client.use { socket ->
                    runCatching {
                        socket.soTimeout = 3500
                        check(socket.inetAddress.isLoopbackAddress)
                        val input = DataInputStream(socket.getInputStream())
                        check(input.readUTF() == PROTOCOL)
                        val clientNonce = input.readUTF()
                        check(clientNonce.length == 64)
                        val serverNonce = nonce()
                        val output = DataOutputStream(socket.getOutputStream())
                        output.apply { writeUTF(proof(key, "server:$clientNonce")); writeUTF(serverNonce); flush() }
                        val token = input.readUTF()
                        check(token.length == 48)
                        check(matches(input.readUTF(), proof(key, "client:$serverNonce:$clientNonce:$token")))
                        val accepted = EmbeddedShellPublisher.publish(service, token)
                        output.apply { writeBoolean(accepted); flush() }
                        if (accepted) Log.i("EmbeddedShellReconnect", "Existing shell service reattached")
                    }.onFailure { Log.w("EmbeddedShellReconnect", "Reconnect handshake rejected: ${it.javaClass.simpleName}") }
                }
            }
        }, "EmbeddedShellReconnect").apply { isDaemon = true; start() }
    }
}
