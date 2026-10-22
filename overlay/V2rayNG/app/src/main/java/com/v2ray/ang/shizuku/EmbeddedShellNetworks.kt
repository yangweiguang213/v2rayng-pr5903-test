package com.v2ray.ang.shizuku

import android.annotation.SuppressLint
import android.net.NetworkRequest
import android.net.NetworkSpecifier

internal object EmbeddedShellNetworks {
    // A generic TEST request can bind to an older TUN, so the new TUN never gets its callback.
    @SuppressLint("PrivateApi")
    fun requestForInterface(interfaceName: String): NetworkRequest {
        require(interfaceName.isNotBlank())
        val specifier = Class.forName("android.net.TestNetworkSpecifier")
            .getConstructor(String::class.java).newInstance(interfaceName) as NetworkSpecifier
        return NetworkRequest.Builder(TetheringPlatformCompat.testNetworkRequest())
            .setNetworkSpecifier(specifier).build()
    }
}
