package com.v2ray.ang.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.v2ray.ang.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

/**
 * Shizuku-style notification pairing. NSD stays alive while Settings owns the screen,
 * including the temporary "pair device" dialog.
 *
 * This Service ONLY pairs the app's TLS ADB key. It never exposes a shell command
 * endpoint or starts hotspot routing on its own.
 */
class EmbeddedAdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discovery: LocalAdbDiscovery? = null
    private var pair: LocalAdbEndpoint? = null
    private var connect: LocalAdbEndpoint? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "v2rayNG 无线调试配对", NotificationManager.IMPORTANCE_HIGH)
        )
        runCatching {
            KadbCert.configure(
                OkioFilePrivateKeyStore(
                    (noBackupFilesDir.absolutePath + "/embedded_adb_private.pem").toPath()
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                runCatching {
                    val notif = notification("正在寻找本机无线调试的配对窗口", "请打开系统设置中的「使用配对码配对设备」")
                    if (Build.VERSION.SDK_INT >= 34) {
                        startForeground(ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else startForeground(ID, notif)
                    beginDiscovery()
                }.onFailure {
                    notifications.notify(ID, notification("后台配对服务启动失败", it.javaClass.simpleName))
                    stopSelf()
                }
            }
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
                val host = intent.getStringExtra(KEY_HOST).orEmpty()
                val port = intent.getIntExtra(KEY_PORT, 0)
                if (code.length != 6 || !code.all(Char::isDigit) || port !in 1..65535 ||
                    host.isBlank()) {
                    notifications.notify(ID, notification("配对码无效", "请重新打开系统配对窗口输入六位数字"))
                } else {
                    notifications.notify(ID, notification("正在配对本机无线调试", "请保持系统无线调试开启"))
                    scope.launch {
                        val result = runCatching { Kadb.pair(host, port, code) }
                        result.fold(
                            onSuccess = {
                                notifications.notify(ID, notification("无线调试配对成功", "返回应用，点击「启动授权服务」"))
                            },
                            onFailure = {
                                notifications.notify(ID, notification("配对失败", it.javaClass.simpleName + "：" + it.message.orEmpty().take(85)))
                            },
                        )
                        if (result.isSuccess) {
                            // Connection port can differ from pairing port; remember only
                            // a validated connect service from local NSD, never pair port.
                            connect?.let { endpoint ->
                                getSharedPreferences("embedded_adb", MODE_PRIVATE).edit()
                                    .putString("host", endpoint.host).putInt("port", endpoint.port).apply()
                            }
                            stopSelf()
                        }
                    }
                }
            }
            ACTION_STOP -> stopSelf()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun beginDiscovery() {
        discovery?.close()
        discovery = LocalAdbDiscovery(this) { pairEndpoint, connectEndpoint, problem ->
            pair = pairEndpoint
            connect = connectEndpoint
            connectEndpoint?.let { endpoint ->
                getSharedPreferences("embedded_adb", MODE_PRIVATE).edit()
                    .putString("host", endpoint.host)
                    .putInt("port", endpoint.port)
                    .apply()
            }
            val notice = when {
                pairEndpoint != null -> notification(
                    "已找到无线调试配对窗口",
                    "从通知中输入系统显示的六位配对码",
                    pairEndpoint,
                )
                problem != null -> notification("本机配对服务发现异常", problem)
                else -> notification("等待系统配对窗口", "进入「无线调试 → 使用配对码配对设备」")
            }
            notifications.notify(ID, notice)
        }.also { it.start() }
    }

    private fun notification(
        title: String,
        description: String,
        endpoint: LocalAdbEndpoint? = null,
    ): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_device_hub_24dp)
            .setContentTitle(title)
            .setContentText(description)
            .setStyle(Notification.BigTextStyle().bigText(description))
            .setOngoing(endpoint == null && title.startsWith("等待"))
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 113,
                Intent(this, ShizukuActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (endpoint != null) {
            val reply = Intent(this, EmbeddedAdbPairingService::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(KEY_HOST, endpoint.host)
                .putExtra(KEY_PORT, endpoint.port)
            val pending = PendingIntent.getForegroundService(
                this,
                112,
                reply,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val input = RemoteInput.Builder(KEY_CODE)
                .setLabel("输入六位配对码")
                .build()
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_device_hub_24dp),
                    "输入配对码",
                    pending,
                ).addRemoteInput(input).build()
            )
        }
        return builder.build()
    }

    override fun onDestroy() {
        discovery?.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.v2ray.ang.embedded.adb.PAIR_START"
        const val ACTION_REPLY = "com.v2ray.ang.embedded.adb.PAIR_REPLY"
        const val ACTION_STOP = "com.v2ray.ang.embedded.adb.PAIR_STOP"
        private const val CHANNEL = "embedded_adb_pairing"
        private const val ID = 20519
        private const val KEY_CODE = "embedded_pairing_code"
        private const val KEY_HOST = "embedded_adb_host"
        private const val KEY_PORT = "embedded_adb_port"
    }
}
