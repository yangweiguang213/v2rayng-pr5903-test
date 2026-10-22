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
 * Shizuku-like ADB TLS mDNS discovery. Tracks each stage so HyperOS failures don't
 * degrade into permanent "searching". Only services resolved on this device are used.
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
    private var foundCount = 0
    private var resolvedCount = 0
    private var ignoredCount = 0
    private var pairService: String? = null
    private var connectService: String? = null
    private val registered = mutableSetOf<NsdManager.DiscoveryListener>()

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else handler.post { if (!closed) action() }
    }

    fun diagnostic(): String =
        "mDNS 广播=$foundCount、解析成功=$resolvedCount、本机校验未通过=$ignoredCount"

    fun start() = onMain {
        if (closed || registered.isNotEmpty()) return@onMain
        discover("_adb-tls-pairing._tcp", true)
        discover("_adb-tls-connect._tcp", false)
    }

    private fun discover(type: String, pairing: Boolean) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) { onMain { registered.remove(this) } }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onMain {
                    registered.remove(this)
                    runCatching { manager.stopServiceDiscovery(this) }
                    update(pair, connect, "无线调试服务发现启动失败（类型=$type，错误=$errorCode）")
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) {
                onMain {
                    if (pairing && info.serviceName == pairService) { pair = null; pairService = null }
                    if (!pairing && info.serviceName == connectService) { connect = null; connectService = null }
                    update(pair, connect, "无线调试服务端口已改变，正在重新发现")
                }
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                // Do not drop OEM-specific names: Shizuku does not filter names either.
                onMain {
                    if (closed) return@onMain
                    foundCount++
                    runCatching {
                        @Suppress("DEPRECATION")
                        manager.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                onMain { update(pair, connect, "mDNS 已发现服务，但解析失败（$errorCode；${diagnostic()}）") }
                            }
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                onMain {
                                    if (closed || serviceInfo.port !in 1..65535) return@onMain
                                    resolvedCount++
                                    val ip = serviceInfo.host?.hostAddress ?: return@onMain
                                    if (!isOwnAddress(ip)) {
                                        ignoredCount++
                                        update(pair, connect, "发现的服务不是本机地址；${diagnostic()}")
                                        return@onMain
                                    }
                                    val endpoint = LocalAdbEndpoint(ip, serviceInfo.port)
                                    if (pairing) { pair = endpoint; pairService = serviceInfo.serviceName }
                                    else { connect = endpoint; connectService = serviceInfo.serviceName }
                                    update(pair, connect, null)
                                }
                            }
                        })
                    }.onFailure { update(pair, connect, "解析本机 ADB 服务失败：${it.javaClass.simpleName}") }
                }
            }
        }
        runCatching {
            manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            registered.add(listener)
        }.onFailure { update(pair, connect, "本机 mDNS 发现不能启动：${it.javaClass.simpleName}") }
    }

    private fun isOwnAddress(address: String): Boolean = runCatching {
        address == "127.0.0.1" || address == "::1" ||
            Collections.list(NetworkInterface.getNetworkInterfaces()).any { nic ->
                Collections.list(nic.inetAddresses).any { local ->
                    local.hostAddress == address ||
                        local.hostAddress.substringBefore('%') == address.substringBefore('%')
                }
            }
    }.getOrDefault(false)

    override fun close() = onMain {
        if (closed) return@onMain
        closed = true
        val listeners = registered.toList()
        registered.clear()
        listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        handler.removeCallbacksAndMessages(null)
    }
}
