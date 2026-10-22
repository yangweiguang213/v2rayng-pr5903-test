package com.v2ray.ang.shizuku

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.v2ray.ang.BuildConfig

/**
 * UID 2000 app_process bootstrap for the existing fail-closed PR5903 tethering service.
 * The --probe argument lets the app distinguish ADB issues from DEX class-loading issues.
 */
object EmbeddedShellStarter {
    private const val TAG = "EmbeddedShellStarter"

    @JvmStatic
    fun main(args: Array<String>) {
        val uid = Process.myUid()
        if (args.contains("--probe")) {
            println("EMBEDDED_APP_PROCESS_OK uid=$uid sdk=${Build.VERSION.SDK_INT}")
            System.out.flush()
            return
        }
        var stage = "validate arguments"
        try {
            check(uid == Process.SHELL_UID) { "Shell UID 2000 required (actual=$uid)" }
            val token = args.firstOrNull { it.startsWith("--token=") }?.substringAfter("=")
            require(!token.isNullOrBlank()) { "Missing session token" }
            val packageName = args.firstOrNull { it.startsWith("--package=") }
                ?.substringAfter("=") ?: BuildConfig.APPLICATION_ID
            require(packageName == BuildConfig.APPLICATION_ID) { "Unexpected package" }
            stage = "prepare main looper"
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()

            // Follow Shizuku-API UserService.create rather than direct service construction
            // from app_process' own class loader.
            stage = "initialize ActivityThread"
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getDeclaredMethod("systemMain").invoke(null)
            val systemContext = threadClass.getDeclaredMethod("getSystemContext").invoke(thread) as Context

            stage = "load installed application context"
            val context = systemContext.createPackageContext(
                packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
            val loadedApk = context.javaClass.getDeclaredField("mPackageInfo").apply {
                isAccessible = true
            }.get(context)
            val application = loadedApk.javaClass
                .getDeclaredMethod("makeApplication", Boolean::class.javaPrimitiveType, Instrumentation::class.java)
                .apply { isAccessible = true }
                .invoke(loadedApk, true, null) as Application
            threadClass.getDeclaredField("mInitialApplication").apply {
                isAccessible = true
                set(thread, application)
            }

            stage = "instantiate privileged tethering service"
            val classLoader = application.classLoader
            val serviceClass = classLoader.loadClass("com.v2ray.ang.shizuku.ShizukuTetheringService")
            val service = serviceClass.getConstructor(Context::class.java)
                .newInstance(application) as IBinder

            stage = "publish Binder to app process"
            check(EmbeddedShellPublisher.publish(service, token)) {
                "Binder provider rejected service or token"
            }
            Log.i(TAG, "Shell tethering service bound successfully")
            println("EMBEDDED_SHELL_READY")
            System.out.flush()
            Looper.loop()
        } catch (t: Throwable) {
            Log.e(TAG, "Embedded Shell failed in: $stage", t)
            // Captured by /data/local/tmp/<log>; logs never include pairing code or token.
            System.err.println("EMBEDDED_SHELL_FAILED stage=$stage cause=${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
            System.err.flush()
        }
    }
}
