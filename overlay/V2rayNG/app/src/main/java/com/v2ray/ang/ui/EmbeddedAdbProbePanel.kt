package com.v2ray.ang.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsDiscoveryState
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.v2ray.ang.shizuku.EmbeddedShellBinderProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

private const val BOOT_LOG = "/data/local/tmp/v2rayng-shell-bootstrap.log"

/** Find only the phone's wireless ADB services; users normally only enter a 6-digit code. */
@Composable
internal fun EmbeddedAdbProbePanel(onReady: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val discovery = remember(app) { KadbMdnsAndroid(app) }
    var state by remember { mutableStateOf(MdnsDiscoveryState()) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在查找无线调试服务……") }
    var advanced by remember { mutableStateOf(false) }
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
    LaunchedEffect(discovery) {
        discovery.start()
        discovery.state.collect { state = it }
    }
    DisposableEffect(discovery) {
        onDispose { discovery.close() }
    }

    fun pairEndpoint(): MdnsEndpoint? = state.pairDevices.firstOrNull()
    fun connectEndpoint(): MdnsEndpoint? = state.connectDevices.firstOrNull()

    suspend fun startWithEndpoint(host: String, port: Int): String = withContext(Dispatchers.IO) {
        require(port in 1..65535)
        val kadb = Kadb.create(host, port, connectTimeout = 6000, socketTimeout = 9000)
        kadb.use {
            val uid = it.shell("id -u")
            check(uid.exitCode == 0 && uid.output.trim() == "2000") {
                "无线调试未获得 Shell 权限（请重新配对）"
            }
            val token = EmbeddedShellBinderProvider.arm()
            val apk = app.applicationInfo.sourceDir.replace("'", "'\"'\"'")
            // Shell-only diagnostic file: no secrets or pairing code are written.
            val command = "umask 077; CLASSPATH='$apk' " +
                "/system/bin/app_process /system/bin " +
                "com.v2ray.ang.shizuku.EmbeddedShellStarter " +
                "--package=${app.packageName} --token=$token " +
                "> $BOOT_LOG 2>&1 </dev/null &"
            val response = it.shell(command)
            check(response.exitCode == 0) {
                "无法启动 Shell 进程：${response.errorOutput.take(120)}"
            }
            var connected = false
            repeat(40) {
                if (EmbeddedShellBinderProvider.binder() != null) connected = true
                if (!connected) Thread.sleep(250)
            }
            if (!connected) {
                val log = it.shell("tail -c 1100 $BOOT_LOG").allOutput.trim()
                val diagnostic = it.shell("logcat -d -t 120 -s EmbeddedShellStarter:V '*:S'")
                    .allOutput.lineSequence()
                    .filter { line -> line.contains("EmbeddedShellStarter") }
                    .toList().takeLast(6).joinToString(" | ").take(500)
                error("内置服务注册失败。Shell输出：${log.takeLast(500).ifBlank { "(空)" }}；" +
                    "启动日志：${diagnostic.ifBlank { "(无)" }}")
            }
        }
        "已启动内置 Shell（UID 2000），无需 Shizuku"
    }

    fun launchUsing(host: String, port: Int) {
        if (busy) return
        busy = true
        status = "正在连接无线调试并启动热点服务……"
        scope.launch {
            val attempt = runCatching { startWithEndpoint(host, port) }
            status = attempt.fold(
                onSuccess = {
                    app.getSharedPreferences("embedded_adb", Context.MODE_PRIVATE).edit()
                        .putString("last_host", host).putInt("last_port", port).apply()
                    onReady()
                    it
                },
                onFailure = { "连接或启动失败：${it.message.orEmpty().take(1000)}" }
            )
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("免 Root 热点授权", style = MaterialTheme.typography.titleMedium)
        Text(
            "在系统「开发者选项 → 无线调试 → 使用配对码配对设备」中查看六位配对码。" +
                "配对和连接端口将自动识别，不需要手工填写。",
            style = MaterialTheme.typography.bodySmall,
        )
        val detectedPair = pairEndpoint()
        val detectedConnect = connectEndpoint()
        Text(
            "自动发现：配对 ${if (detectedPair != null) "已找到" else "查找中"} · " +
                "连接 ${if (detectedConnect != null) "已找到" else "查找中"}",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("六位无线调试配对码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && code.length == 6 && (detectedPair != null ||
                advanced && manualHost.isNotBlank() && (manualPairPort.toIntOrNull()?.let { it in 1..65535 } == true)),
            onClick = {
                val endpoint = detectedPair
                val host = endpoint?.host ?: manualHost
                val port = endpoint?.port ?: manualPairPort.toIntOrNull() ?: 0
                val currentCode = code
                code = ""
                busy = true
                status = "正在配对……"
                scope.launch {
                    val outcome = runCatching {
                        withContext(Dispatchers.IO) { Kadb.pair(host, port, currentCode) }
                        // The _adb-tls-connect service may appear shortly after pairing.
                        var target = connectEndpoint()
                        repeat(35) {
                            if (target == null) {
                                delay(250)
                                target = connectEndpoint()
                            }
                        }
                        val connectedHost = target?.host ?: manualHost.takeIf { it.isNotBlank() }
                            ?: app.getSharedPreferences("embedded_adb", Context.MODE_PRIVATE)
                                .getString("last_host", null)
                        val connectedPort = target?.port ?: manualConnectPort.toIntOrNull()
                            ?: app.getSharedPreferences("embedded_adb", Context.MODE_PRIVATE)
                                .getInt("last_port", 0)
                        check(!connectedHost.isNullOrBlank() && connectedPort in 1..65535) {
                            "配对完成，但未发现无线调试连接端口。请保持 Wi-Fi/无线调试开启，稍后点击「启动服务」。"
                        }
                        startWithEndpoint(requireNotNull(connectedHost), connectedPort)
                    }
                    status = outcome.fold(
                        onSuccess = { onReady(); "配对成功；$it" },
                        onFailure = { "操作未完成：${it.message.orEmpty().take(1000)}" },
                    )
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "处理中……" else "配对并启动热点服务") }

        val remembered = app.getSharedPreferences("embedded_adb", Context.MODE_PRIVATE)
        val host = detectedConnect?.host ?: manualHost.takeIf { it.isNotBlank() }
            ?: remembered.getString("last_host", null)
        val port = detectedConnect?.port ?: manualConnectPort.toIntOrNull()
            ?: remembered.getInt("last_port", 0)
        OutlinedButton(
            enabled = !busy && !host.isNullOrBlank() && port in 1..65535,
            onClick = { launchUsing(host!!, port) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("已配对？直接启动服务") }

        Text(status, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
            Text(if (advanced) "收起高级设置" else "无法自动发现？展开高级设置")
        }
        if (advanced) {
            OutlinedTextField(
                value = manualHost, onValueChange = { manualHost = it },
                label = { Text("手机的 Wi-Fi IP（例如 10.10.0.11）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = manualPairPort,
                    onValueChange = { manualPairPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("配对端口") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = manualConnectPort,
                    onValueChange = { manualConnectPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("连接端口") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
