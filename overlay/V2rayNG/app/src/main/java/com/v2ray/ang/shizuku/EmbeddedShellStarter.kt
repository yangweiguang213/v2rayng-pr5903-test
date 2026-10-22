package com.v2ray.ang.shizuku

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.v2ray.ang.BuildConfig

/**
 * Shell-UID entry point launched by /system/bin/app_process after user-authorized
 * wireless ADB pairing. The existing test-network/HEV/Xray service is unchanged.
 *
 * The launcher is intentionally not an Android Service: a regular Service would
 * run under the app UID and lose the required shell networking privileges.
 */
object EmbeddedShellStarter {
    private const val TAG = "EmbeddedShellStarter"

    @JvmStatic
    fun main(args: Array<String>) {
        if (Process.myUid() != Process.SHELL_UID) {
            Log.e(TAG, "Refusing non-shell UID")
            return
        }
        val token = args.firstOrNull { it.startsWith("--token=") }?.substringAfter("=")
        if (token.isNullOrBlank()) return
        val packageName = args.firstOrNull { it.startsWith("--package=") }?.substringAfter("=")
            ?: BuildConfig.APPLICATION_ID
        if (packageName != BuildConfig.APPLICATION_ID) return

        try {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val clazz = Class.forName("android.app.ActivityThread")
            val thread = clazz.getDeclaredMethod("systemMain").invoke(null)
            val systemContext = clazz.getDeclaredMethod("getSystemContext").invoke(thread) as Context

            // Reproduce only Shizuku UserService's app context creation.
            val context = systemContext.createPackageContext(
                packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
            val loadedApk = context.javaClass.getDeclaredField("mPackageInfo").apply {
                isAccessible = true
            }.get(context)
            val makeApplication = loadedApk.javaClass.declaredMethods.first {
                it.name == "makeApplication" && it.parameterTypes.size == 2
            }.apply { isAccessible = true }
            val application = makeApplication.invoke(loadedApk, true, null) as Context
            clazz.getDeclaredField("mInitialApplication").apply {
                isAccessible = true
                set(thread, application)
            }

            val service: IBinder = ShizukuTetheringService(application)
            val shellContext = ShellContextCompat.create(application)
            val extras = Bundle().apply {
                putString(EmbeddedShellBinderProvider.KEY_TOKEN, token)
                putBinder(EmbeddedShellBinderProvider.KEY_BINDER, service)
            }
            val reply = shellContext.contentResolver.call(
                Uri.parse("content://" + EmbeddedShellBinderProvider.authority()),
                EmbeddedShellBinderProvider.METHOD_PUBLISH,
                null,
                extras,
            )
            check(reply?.getBoolean(EmbeddedShellBinderProvider.KEY_OK) == true) {
                "Embedded shell Binder registration refused"
            }
            Log.i(TAG, "Embedded tethering Shell service started (Android ${Build.VERSION.SDK_INT})")
            Looper.loop()
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to initialize embedded shell service", e)
        }
    }
}
