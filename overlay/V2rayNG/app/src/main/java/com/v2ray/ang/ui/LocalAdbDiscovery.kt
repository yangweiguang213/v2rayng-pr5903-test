package com.v2ray.ang.ui

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.NetworkInterface
import java.util.Collections

internal data class LocalAdbEndpoint(val host: String, val port: Int)

/**
 * Shizuku-style, legacy one-shot NSD resolver for the phone's own Wireless debugging.
 * No registerServiceInfoCallback, no device picker, no periodic scans and no UI blocking.
 * Callbacks and stop() are marshalled onto the main looper; cancellation is idempotent.
 */
internal class LocalAdbDiscovery(
    context: Context,
    private val update: (LocalAdbEndpoint?, LocalAdbEndpoint?, String?) -> Unit,
) : AutoCloseable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var pair: LocalAdbEndpoint? = null
    private var connect: LocalAdbEndpoint? = null
    private var closed = false
    private val registered = mutableSetOf<NsdManager.DiscoveryListener>()

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post {
            if (!closed) action()
        }
    }

    fun start() = onMain {
        if (closed || registered.isNotEmpty()) return@onMain
        discover("_adb-tls-pairing._tcp", true)
        discover("_adb-tls-connect._tcp", false)
    }

    private fun discover(type: String, pairing: Boolean) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) {
                onMain { registered.remove(this) }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onMain {
                    registered.remove(this)
                    runCatching { manager.stopServiceDiscovery(this) }
                    update(pair, connect, "ADB 服务发现失败（$errorCode），可使用高级设置")
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                onMain { registered.remove(this) }
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith("adb-", ignoreCase = true)) return
                // Legacy one-shot resolution matches Shizuku's proven pairing logic.
                onMain {
                    if (closed) return@onMain
                    runCatching {
                        @Suppress("DEPRECATION")
                        manager.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                onMain {
                                    if (closed || serviceInfo.port !in 1..65535) return@onMain
                                    val resolved = serviceInfo.host?.hostAddress ?: return@onMain
                                    if (!isOurOwnAddress(resolved)) return@onMain
                                    val endpoint = LocalAdbEndpoint(resolved, serviceInfo.port)
                                    if (pairing) pair = endpoint else connect = endpoint
                                    update(pair, connect, null)
                                }
                            }
                        })
                    }.onFailure { update(pair, connect, "ADB 端口解析失败，尝试高级设置") }
                }
            }
        }
        runCatching {
            manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            registered.add(listener)
        }.onFailure { update(pair, connect, "无法启动本机无线调试发现：${it.javaClass.simpleName}") }
    }

    private fun isOurOwnAddress(address: String): Boolean = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).any { network ->
            Collections.list(network.inetAddresses).any { it.hostAddress == address }
        }
    }.getOrDefault(false)

    override fun close() = onMain {
        if (closed) return@onMain
        closed = true
        val toStop = registered.toList()
        registered.clear()
        toStop.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        handler.removeCallbacksAndMessages(null)
    }
}
