package com.v2ray.ang.shizuku

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.Process
import com.v2ray.ang.BuildConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** Reattach to the existing shell process without an ADB connection or a port scan. */
internal object EmbeddedShellReconnect {
    private const val KEY = "reconnect_secret"
    private const val PROTOCOL = "v2rayng-reconnect-1"
    private val socketName get() = BuildConfig.APPLICATION_ID + ".shell-reconnect"

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
            LocalSocket().use { socket ->
                socket.soTimeout = 3500
                socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                check(socket.peerCredentials.uid == Process.SHELL_UID) { "Unexpected service owner" }
                val token = EmbeddedShellBinderProvider.arm()
                DataOutputStream(socket.outputStream).apply {
                    writeUTF(PROTOCOL); writeUTF(key); writeUTF(token); flush()
                }
                DataInputStream(socket.inputStream).readBoolean() && EmbeddedShellBinderProvider.binder() != null
            }
        }.getOrDefault(false)
    }

    // Bind before creating a service: a second launch cannot create another routing owner.
    fun reserve(): LocalServerSocket = LocalServerSocket(socketName)

    fun serve(server: LocalServerSocket, service: IBinder, key: String, appUid: Int) {
        require(key.length == 64)
        Thread({
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                client.use { socket ->
                    runCatching {
                        socket.soTimeout = 3500
                        check(socket.peerCredentials.uid == appUid) { "Unexpected reconnect caller" }
                        val input = DataInputStream(socket.inputStream)
                        check(input.readUTF() == PROTOCOL)
                        check(MessageDigest.isEqual(input.readUTF().toByteArray(), key.toByteArray()))
                        val token = input.readUTF()
                        check(token.length == 48)
                        val accepted = EmbeddedShellPublisher.publish(service, token)
                        DataOutputStream(socket.outputStream).apply { writeBoolean(accepted); flush() }
                    }
                }
            }
        }, "EmbeddedShellReconnect").apply { isDaemon = true; start() }
    }
}
