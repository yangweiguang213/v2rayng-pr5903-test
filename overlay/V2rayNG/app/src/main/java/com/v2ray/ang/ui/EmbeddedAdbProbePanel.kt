package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.v2ray.ang.shizuku.EmbeddedShellBinderProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

private const val BOOT_LOG = "/data/local/tmp/v2rayng-shell-bootstrap.log"

@Composable
internal fun EmbeddedAdbProbePanel(onReady: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val preferences = remember(app) { app.getSharedPreferences("embedded_adb", Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    var serviceReady by remember { mutableStateOf(EmbeddedShellBinderProvider.binder() != null) }
    var connectEndpoint by remember { mutableStateOf<LocalAdbEndpoint?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }
    var discoveryAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 37 ||
        app.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var scanStatus by remember { mutableStateOf("正在查找本机调试服务…") }

    remember(app) {
        KadbCert.configure(OkioFilePrivateKeyStore((app.noBackupFilesDir.absolutePath + "/embedded_adb_private.pem").toPath()))
        true
    }
    val discovery = remember(app, revision, discoveryAllowed, serviceReady) {
        LocalAdbDiscovery(app) { _, connect, problem ->
            connectEndpoint = connect
            scanError = problem
            scanStatus = problem ?: if (connect != null) "已找到本机调试服务" else "正在查找本机调试服务…"
        }
    }
    DisposableEffect(discovery) {
        if (discoveryAllowed && !serviceReady) discovery.start()
        onDispose { discovery.close() }
    }
    LaunchedEffect(discovery) {
        delay(9000)
        if (!serviceReady && discoveryAllowed && scanError == null && connectEndpoint == null)
            scanStatus = "未找到调试服务，请开启系统无线调试后重新查找。"
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            val ready = EmbeddedShellBinderProvider.binder() != null
            if (ready != serviceReady) { serviceReady = ready; onReady() }
        }
    }

    fun beginNotificationPairing() {
        runCatching {
            app.startForegroundService(Intent(app, EmbeddedAdbPairingService::class.java)
                .setAction(EmbeddedAdbPairingService.ACTION_START))
        }.onSuccess {
            result = "配对通知已开启，请在通知栏填写系统显示的六位配对码。"
            runCatching {
                app.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                runCatching { app.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }.onFailure { result = "配对通知启动失败，请重试。" }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        discoveryAllowed = Build.VERSION.SDK_INT < 37 || app.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notificationAllowed = Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (discoveryAllowed && notificationAllowed) beginNotificationPairing()
        else result = "请允许本地网络和通知权限，以完成通知栏配对。"
    }
    fun pairFromNotification() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 37 && !discoveryAllowed) add("android.permission.ACCESS_LOCAL_NETWORK")
            if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) beginNotificationPairing() else permissions.launch(missing.toTypedArray())
    }

    suspend fun startService(endpoint: LocalAdbEndpoint): String = withContext(Dispatchers.IO) {
        if (EmbeddedShellBinderProvider.binder() != null) return@withContext "内置服务已在运行"
        check(endpoint.port in 1..65535) { "连接端口无效" }
        Kadb.create(endpoint.host, endpoint.port, connectTimeout = 6000, socketTimeout = 12000).use { adb ->
            val uid = adb.shell("id -u")
            check(uid.exitCode == 0 && uid.output.trim() == "2000") {
                "ADB 连接未通过 UID 2000 检查：${uid.allOutput.take(150)}"
            }
            val apk = app.applicationInfo.sourceDir.replace("'", "'\"'\"'")
            val access = adb.shell("test -r '$apk' && echo APK_READ_OK")
            check(access.exitCode == 0 && access.allOutput.contains("APK_READ_OK")) {
                "ADB Shell 无法读取已安装 APK（类加载前检查）：${access.allOutput.take(180)}"
            }
            // Unlike the old fire-and-forget bootstrap, this probe executes in the
            // foreground and returns the exact app_process/DEX class-loading error.
            val probe = adb.shell(
                "CLASSPATH='$apk' /system/bin/app_process /system/bin " +
                    "com.v2ray.ang.shizuku.EmbeddedShellStarter --probe"
            )
            check(probe.exitCode == 0 && probe.allOutput.contains("EMBEDDED_APP_PROCESS_OK uid=2000")) {
                "Shell classloader 自检失败（UID 已通过）：exit=${probe.exitCode}，" +
                    probe.allOutput.takeLast(800).ifBlank { "(无输出)" }
            }

            val token = EmbeddedShellBinderProvider.arm()
            // A fresh boot marker distinguishes launch failure from missing Binder IPC.
            val command = "umask 077; printf 'APP_PROCESS_QUEUED\\n' > $BOOT_LOG; " +
                "CLASSPATH='$apk' /system/bin/app_process /system/bin " +
                "--nice-name=v2rayng_embedded_shell " +
                "com.v2ray.ang.shizuku.EmbeddedShellStarter " +
                "--package=${app.packageName} --token=$token >> $BOOT_LOG 2>&1 </dev/null & " +
                "echo LAUNCH_SENT"
            val launched = adb.shell(command)
            check(launched.exitCode == 0 && launched.allOutput.contains("LAUNCH_SENT")) {
                "Shell 进程提交失败：${launched.allOutput.takeLast(300)}"
            }

            var connected = false
            for (i in 0 until 40) {
                if (EmbeddedShellBinderProvider.binder() != null) {
                    connected = true
                    break
                }
                Thread.sleep(250)
            }
            if (!connected) {
                val diagnostic = runCatching { adb.shell("tail -c 2100 $BOOT_LOG").allOutput.trim() }
                    .getOrElse { "(无法读取引导日志：${it.javaClass.simpleName})" }
                val ps = runCatching {
                    adb.shell("ps -A | grep v2rayng_embedded_shell").allOutput.trim()
                }.getOrDefault("")
                error(
                    "内置 Shell 未注册。前置检查：ADB UID/DEX 均通过。" +
                        "进程：${if (ps.isBlank()) "未检测到" else "仍在运行"}。" +
                        "启动日志：${diagnostic.takeLast(850).ifBlank { "(日志文件空，需进一步检查系统限制)" }}"
                )
            }
        }
        "已建立内置 Shell 网络共享服务"
    }


    fun start() {
        if (busy) return
        if (EmbeddedShellBinderProvider.binder() != null) { serviceReady = true; onReady(); return }
        val endpoint = connectEndpoint ?: preferences.getInt("port", 0).takeIf { it in 1..65535 }?.let {
            LocalAdbEndpoint(preferences.getString("host", "127.0.0.1") ?: "127.0.0.1", it)
        }
        if (endpoint == null) { result = "请开启无线调试并完成通知栏配对后重试。"; return }
        busy = true
        result = "正在启动授权服务…"
        scope.launch {
            val outcome = runCatching { startService(endpoint) }
            outcome.onSuccess {
                preferences.edit().putString("host", endpoint.host).putInt("port", endpoint.port).apply()
                serviceReady = true
                result = ""
                onReady()
            }.onFailure {
                result = "启动失败，请确认已完成通知栏配对并保持无线调试开启。"
                android.util.Log.w("EmbeddedAuthorization", "Authorization service start failed", it)
            }
            busy = false
        }
    }

    HotspotSectionCard {
        HotspotSectionHeader(
            iconRes = com.v2ray.ang.R.drawable.ic_device_hub_24dp,
            title = "内置授权服务状态",
            status = if (serviceReady) "已就绪" else if (busy) "启动中" else "未启动",
            active = serviceReady,
        )
        Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
            Text("首次配对：开启系统无线调试，在通知栏填写六位配对码。完成后返回这里启动服务。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(14.dp))
        }
        if (!serviceReady) {
            Button(onClick = { pairFromNotification() }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("在通知栏配对") }
            OutlinedButton(onClick = { start() }, enabled = !busy && discoveryAllowed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (busy) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (busy) "正在启动…" else "启动授权服务")
            }
            if (discoveryAllowed) Text(scanStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (result.isNotBlank() && !serviceReady) Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                serviceReady = EmbeddedShellBinderProvider.binder() != null
                connectEndpoint = null; scanError = null; result = ""; revision++; onReady()
            }, enabled = !busy) { Text(if (serviceReady) "刷新状态" else "重新查找") }
            if (serviceReady) TextButton(onClick = { pairFromNotification() }) { Text("重新配对") }
        }
    }
}
