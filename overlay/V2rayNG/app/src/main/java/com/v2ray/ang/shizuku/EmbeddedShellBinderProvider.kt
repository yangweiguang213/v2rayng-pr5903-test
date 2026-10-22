package com.v2ray.ang.shizuku

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import com.v2ray.ang.BuildConfig
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Receives the privileged Binder from a separately started shell-UID app_process.
 *
 * This provider offers NO public commands to the shell. A fresh, short-lived session
 * token must be armed locally by an explicit UI action. Only UID 2000 may publish.
 */
class EmbeddedShellBinderProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_PUBLISH) return null
        if (Binder.getCallingUid() != Process.SHELL_UID) throw SecurityException("Not ADB shell")
        val expected = pendingToken.getAndSet(null) ?: return null
        val provided = extras?.getString(KEY_TOKEN) ?: return null
        if (expected != provided) return null
        val candidate = extras.getBinder(KEY_BINDER) ?: return null
        if (!candidate.pingBinder()) return null
        val previous = activeBinder.getAndSet(candidate)
        if (previous !== candidate) {
            runCatching {
                candidate.linkToDeath({ activeBinder.compareAndSet(candidate, null) }, 0)
            }
        }
        return Bundle().apply { putBoolean(KEY_OK, true) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val METHOD_PUBLISH = "publish"
        const val KEY_TOKEN = "token"
        const val KEY_BINDER = "binder"
        const val KEY_OK = "ok"
        private val pendingToken = AtomicReference<String?>()
        private val activeBinder = AtomicReference<IBinder?>()
        private val random = SecureRandom()

        fun authority(): String = BuildConfig.APPLICATION_ID + ".embedded-shell"

        fun arm(): String {
            val bytes = ByteArray(24)
            random.nextBytes(bytes)
            val token = bytes.joinToString("") { "%02x".format(it) }
            pendingToken.set(token)
            return token
        }

        fun binder(): IBinder? = activeBinder.get()?.takeIf { it.pingBinder() }
        fun service(): IShizukuTetheringService? =
            binder()?.let { IShizukuTetheringService.Stub.asInterface(it) }
    }
}
