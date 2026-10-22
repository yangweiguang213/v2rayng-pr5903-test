package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var scanGeneration by remember { mutableIntStateOf(0) }
    var pairEndpoint by remember { mutableStateOf<LocalAdbEndpoint?>(null) }
    var connectEndpoint by remember { mutableStateOf<LocalAdbEndpoint?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var scanStatus by remember { mutableStateOf("正在识别本机无线调试端口…") }
    var scanError by remember { mutableStateOf<String?>(null) }
    var discoveryAllowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 37 ||
            app.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    var result by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var manualPairPort by remember { mutableStateOf("") }
    var manualConnectPort by remember { mutableStateOf("") }

    remember(app) {
        KadbCert.configure(
            OkioFilePrivateKeyStore(
                (app.noBackupFilesDir.absolutePath + "/embedded_adb_private.pem").toPath()
            )
        )
        true
    }

    val networkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        discoveryAllowed = granted
        scanError = null
        scanStatus = if (granted) "正在查找本机无线调试…" else "需要允许本地网络访问，才能自动查找本机调试服务。"
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        result = if (granted) "通知权限已开启，请点击通知栏配对。" else "通知未开启，可在分屏模式下输入配对码。"
    }
    val discovery = remember(app, scanGeneration, discoveryAllowed) {
        LocalAdbDiscovery(app) { pair, connect, problem ->
            pairEndpoint = pair
            connectEndpoint = connect
            scanError = problem
            if (problem != null) {
                scanStatus = problem
            } else if (pair != null || connect != null) {
                scanStatus = "已发现本机无线调试服务"
            }
        }
    }
    DisposableEffect(discovery) {
        if (discoveryAllowed) runCatching { discovery.start() }.onFailure {
            scanError = "自动发现无法启动：${it.javaClass.simpleName}"
            scanStatus = scanError.orEmpty()
        }
        onDispose { runCatching { discovery.close() } }
    }
    LaunchedEffect(discovery) {
        delay(9000)
        if (discoveryAllowed && scanError == null && pairEndpoint == null && connectEndpoint == null) {
            scanStatus = "9 秒内未发现本机 ADB：${discovery.diagnostic()}。可检查系统无线调试并重扫；若广播为 0，请使用高级设置。"
        }
    }

    suspend fun startService(endpoint: LocalAdbEndpoint): String = withContext(Dispatchers.IO) {
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

    fun start(endpoint: LocalAdbEndpoint) {
        if (busy) return
        busy = true
        result = "正在启动内置网络共享服务…"
        scope.launch {
            val outcome = runCatching { startService(endpoint) }
            result = outcome.fold(
                onSuccess = {
                    app.getSharedPreferences("embedded_adb", Context.MODE_PRIVATE).edit()
                        .putString("host", endpoint.host).putInt("port", endpoint.port).apply()
                    onReady()
                    it
                },
                onFailure = { "启动失败：${it.message.orEmpty().take(1100)}" },
            )
            busy = false
        }
    }

    val preferences = remember(app) { app.getSharedPreferences("embedded_adb", Context.MODE_PRIVATE) }
    val preferredHost = manualHost.takeIf { it.isNotBlank() }
    val discoveredConnect = connectEndpoint
    val startEndpoint = discoveredConnect
        ?: manualConnectPort.toIntOrNull()?.takeIf { it in 1..65535 }?.let {
            LocalAdbEndpoint(preferredHost ?: "127.0.0.1", it)
        }
        ?: preferences.getInt("port", 0).takeIf { it in 1..65535 }?.let {
            LocalAdbEndpoint(preferences.getString("host", "127.0.0.1") ?: "127.0.0.1", it)
        }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("免 Root 热点授权", style = MaterialTheme.typography.titleMedium)
        if (!discoveryAllowed) {
            Text("请允许本地网络访问，以查找这台手机的无线调试端口。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { networkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK") },
                modifier = Modifier.fillMaxWidth()) { Text("允许并查找本机调试服务") }
        }
        Text(
            "推荐使用通知栏配对，像 Shizuku 一样不退出系统配对窗口。后台保持发现，直接在通知里输入六位数字。",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            enabled = !busy && discoveryAllowed,
            onClick = {
                if (Build.VERSION.SDK_INT >= 33 &&
                    app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    runCatching {
                        app.startForegroundService(
                            Intent(app, EmbeddedAdbPairingService::class.java)
                                .setAction(EmbeddedAdbPairingService.ACTION_START)
                        )
                    }.onSuccess {
                        result = "配对通知已启动。现在打开系统「无线调试 → 使用配对码配对设备」，展开通知栏输入配对码。"
                    }.onFailure {
                        result = "无法启动通知栏配对服务：${it.javaClass.simpleName} ${it.message.orEmpty().take(120)}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("使用通知栏配对（推荐）") }
        Text(
            "下方的配对码输入框仅适用于分屏或悬浮窗口中保持系统配对弹窗开启的情况。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "配对端口：${if (pairEndpoint != null) "已发现" else "未发现"} · " +
                "连接端口：${if (connectEndpoint != null) "已发现" else "未发现"}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (scanStatus.isNotBlank()) {
            Text(scanStatus, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("六位配对码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val manualPair = manualPairPort.toIntOrNull()?.takeIf { it in 1..65535 }?.let {
            LocalAdbEndpoint(preferredHost ?: "127.0.0.1", it)
        }
        Button(
            enabled = !busy && code.length == 6 && (pairEndpoint != null || (advanced && manualPair != null)),
            onClick = {
                val endpoint = pairEndpoint ?: manualPair ?: return@Button
                val pairingCode = code
                code = ""
                busy = true
                result = "正在配对本机无线调试…"
                scope.launch {
                    val outcome = runCatching {
                        withContext(Dispatchers.IO) {
                            Kadb.pair(endpoint.host, endpoint.port, pairingCode)
                        }
                        result = "配对成功，正在启动服务…"
                        var connect = startEndpoint
                        for (i in 0 until 32) {
                            if (connect != null) break
                            delay(250)
                            connect = connectEndpoint
                        }
                        checkNotNull(connect) {
                            "配对成功，但未发现连接端口。可重新扫描或使用高级设置。"
                        }
                        startService(connect)
                    }
                    result = outcome.fold(
                        onSuccess = {
                            val p = connectEndpoint ?: startEndpoint
                            if (p != null) preferences.edit()
                                .putString("host", p.host).putInt("port", p.port).apply()
                            onReady()
                            "配对成功，$it"
                        },
                        onFailure = { "配对或启动失败：${it.message.orEmpty().take(1100)}" },
                    )
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "正在处理…" else "配对并启动") }
        OutlinedButton(
            enabled = !busy && startEndpoint != null,
            onClick = { startEndpoint?.let { start(it) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("已经配对？直接启动") }
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    pairEndpoint = null
                    connectEndpoint = null
                    scanStatus = "正在重新扫描本机无线调试…"
                    scanError = null
                    scanGeneration++
                },
                modifier = Modifier.weight(1f),
            ) { Text("重新扫描") }
            OutlinedButton(
                onClick = { advanced = !advanced },
                modifier = Modifier.weight(1f),
            ) { Text(if (advanced) "收起高级设置" else "高级设置") }
        }
        if (advanced) {
            Text("可使用系统「无线调试」主页面的 IP / 连接端口和配对弹窗中的配对端口。注意：每次开启无线调试端口可能变化。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = manualHost, onValueChange = { manualHost = it.trim() },
                label = { Text("本机地址（通常 127.0.0.1）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = manualPairPort,
                    onValueChange = { manualPairPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("配对端口") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = manualConnectPort,
                    onValueChange = { manualConnectPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("连接端口") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
