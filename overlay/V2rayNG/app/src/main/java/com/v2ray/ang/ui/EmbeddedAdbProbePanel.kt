package com.v2ray.ang.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.v2ray.ang.shizuku.EmbeddedShellBinderProvider
import kotlinx.coroutines.delay
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Experimental first stage of Shizuku-free tethering.
 *
 * This verifies that v2rayNG itself can pair with the phone's adbd and obtain shell UID
 * without installing another app. IMPORTANT: this only tests the bootstrap channel.
 * The protected TUN still uses the existing Shizuku UserService until a replacement
 * privileged service and authenticated Binder IPC are implemented and tested.
 */
@Composable
internal fun EmbeddedAdbProbePanel() {
    var host by remember { mutableStateOf("127.0.0.1") }
    var pairPort by remember { mutableStateOf("") }
    var connectionPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("尚未连接 ADB。此页仅用于单 APK 权限链路验证，不能代替 Shizuku 热点服务。") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("内置 ADB 权限测试（实验）", style = MaterialTheme.typography.titleMedium)
        Text(
            "打开系统开发者选项 → 无线调试，查看「使用配对码配对设备」中的端口和配对码。" +
                "配对端口与无线调试主页面的连接端口不是同一个端口。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("本机 ADB 地址（默认 127.0.0.1）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pairPort,
            onValueChange = { pairPort = it.filter(Char::isDigit).take(5) },
            label = { Text("配对端口") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
            label = { Text("六位配对码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = connectionPort,
            onValueChange = { connectionPort = it.filter(Char::isDigit).take(5) },
            label = { Text("无线调试连接端口（非配对端口）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && host.isNotBlank() && (pairPort.toIntOrNull()?.let { it in 1..65535 } == true) && pairingCode.length == 6,
                onClick = {
                    busy = true
                    result = "正在配对……"
                    val pairingHost = host.trim()
                    val pairingPort = pairPort.toInt()
                    val code = pairingCode
                    scope.launch {
                        val attempt = runCatching {
                            withContext(Dispatchers.IO) { Kadb.pair(pairingHost, pairingPort, code) }
                        }
                        pairingCode = ""
                        result = attempt.fold(
                            onSuccess = { "ADB 配对成功。请使用连接端口测试 Shell 身份。" },
                            onFailure = { "配对失败：${it.javaClass.simpleName}（检查端口、配对窗口和无线调试）" },
                        )
                        busy = false
                    }
                },
            ) { Text("配对") }
            Button(
                enabled = !busy && host.isNotBlank() && (connectionPort.toIntOrNull()?.let { it in 1..65535 } == true),
                onClick = {
                    busy = true
                    result = "正在检查 Shell 身份……"
                    val connectHost = host.trim()
                    val port = connectionPort.toInt()
                    scope.launch {
                        val attempt = runCatching {
                            withContext(Dispatchers.IO) {
                                Kadb.create(connectHost, port, connectTimeout = 5000, socketTimeout = 5000).use {
                                    it.shell("id -u")
                                }
                            }
                        }
                        result = attempt.fold(
                            onSuccess = {
                                if (it.exitCode == 0 && it.output.trim() == "2000") {
                                    "ADB Shell 权限检查成功（UID=2000）。尚未启用内置热点路由服务。"
                                } else {
                                    "未获得预期 Shell 身份。退出码=${it.exitCode}"
                                }
                            },
                            onFailure = { "连接失败：${it.javaClass.simpleName}（检查无线调试主连接端口）" },
                        )
                        busy = false
                    }
                },
            ) { Text("检查 Shell") }
        }
        Button(
            enabled = !busy && host == "127.0.0.1" &&
                (connectionPort.toIntOrNull()?.let { it in 1..65535 } == true),
            onClick = {
                busy = true
                result = "正在启动内置 Shell 热点服务……"
                val port = connectionPort.toInt()
                scope.launch {
                    val attempt = runCatching {
                        val token = EmbeddedShellBinderProvider.arm()
                        withContext(Dispatchers.IO) {
                            val apk = app.applicationInfo.sourceDir.replace("'", "'\"'\"'")
                            val command = "CLASSPATH='$apk' /system/bin/app_process /system/bin " +
                                "com.v2ray.ang.shizuku.EmbeddedShellStarter " +
                                "--package=${app.packageName} --token=$token " +
                                ">/dev/null 2>&1 </dev/null &"
                            Kadb.create("127.0.0.1", port, connectTimeout = 5000, socketTimeout = 10000).use {
                                val uidResponse = it.shell("id -u")
                                check(uidResponse.exitCode == 0 && uidResponse.output.trim() == "2000") {
                                    "ADB is not shell UID 2000"
                                }
                                val start = it.shell(command)
                                check(start.exitCode == 0) { "Shell process startup rejected" }
                            }
                        }
                        var alive = false
                        repeat(40) {
                            if (EmbeddedShellBinderProvider.binder() != null) alive = true
                            if (!alive) delay(250)
                        }
                        check(alive) { "Shell Binder registration timed out; check logcat EmbeddedShellStarter" }
                    }
                    result = attempt.fold(
                        onSuccess = { "内置 Shell 服务已注册。点击下方刷新后可以检查热点路由。" },
                        onFailure = { "启动未成功：${it.javaClass.simpleName}：${it.message.orEmpty().take(120)}" },
                    )
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("启动内置 Shell 热点服务（实验）") }
        Text(result, style = MaterialTheme.typography.bodySmall)
        Text(
            "安全提示：仅本机调试使用；建议测试完关闭无线调试。此功能不会自动调用高权限命令。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
