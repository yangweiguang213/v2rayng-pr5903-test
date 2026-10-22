package com.v2ray.ang.shizuku

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process

/** app_process is not registered with AMS as an application process. */
internal object EmbeddedShellPublisher {
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun publish(service: IBinder, token: String): Boolean {
        check(Process.myUid() == Process.SHELL_UID)
        val authority = EmbeddedShellBinderProvider.authority()
        val amInterface = Class.forName("android.app.IActivityManager")
        val am = if (Build.VERSION.SDK_INT >= 26) {
            Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null)
        } else {
            Class.forName("android.app.ActivityManagerNative").getDeclaredMethod("getDefault").invoke(null)
        }
        val acquire = amInterface.methods.single {
            it.name == "getContentProviderExternal" && it.parameterCount == (if (Build.VERSION.SDK_INT >= 29) 4 else 3)
        }
        // A null token uses the framework's counted external-provider reference,
        // matching Shizuku's acquisition/release pair. Never use ContentResolver:
        // it supplies an IApplicationThread which AMS cannot find for app_process.
        val args = if (acquire.parameterCount == 4)
            arrayOf<Any?>(authority, Process.myUid() / 100000, null, "v2rayng-shell")
        else arrayOf<Any?>(authority, Process.myUid() / 100000, null)
        val holder = checkNotNull(acquire.invoke(am, *args)) { "External provider unavailable" }
        try {
            val provider = checkNotNull(holder.javaClass.getField("provider").get(holder))
            val extras = Bundle().apply {
                putString(EmbeddedShellBinderProvider.KEY_TOKEN, token)
                putBinder(EmbeddedShellBinderProvider.KEY_BINDER, service)
            }
            val callArgs = when {
                Build.VERSION.SDK_INT >= 31 -> arrayOf<Any?>(
                    AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build(),
                    authority, EmbeddedShellBinderProvider.METHOD_PUBLISH, null, extras,
                )
                Build.VERSION.SDK_INT >= 30 -> arrayOf<Any?>("com.android.shell", null, authority,
                    EmbeddedShellBinderProvider.METHOD_PUBLISH, null, extras)
                Build.VERSION.SDK_INT >= 29 -> arrayOf<Any?>("com.android.shell", authority,
                    EmbeddedShellBinderProvider.METHOD_PUBLISH, null, extras)
                else -> arrayOf<Any?>("com.android.shell", EmbeddedShellBinderProvider.METHOD_PUBLISH, null, extras)
            }
            val method = Class.forName("android.content.IContentProvider").methods.single {
                it.name == "call" && it.parameterCount == callArgs.size
            }
            val reply = method.invoke(provider, *callArgs) as? Bundle
            return reply?.getBoolean(EmbeddedShellBinderProvider.KEY_OK) == true
        } finally {
            amInterface.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
                .invoke(am, authority, null)
        }
    }
}
