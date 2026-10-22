package com.v2ray.ang.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppTopBar

@Composable
internal fun PolishedTetheringScreen(
    state: TetheringUiState,
    onBackClick: () -> Unit,
    onAction: (ShizukuAction) -> Unit,
) {
    val context = LocalContext.current
    val route = routingAction(state)
    val hotspot = hotspotAction(state)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = { AppTopBar(title = "热点共享", onBackClick = onBackClick,
            isLoading = state.operation.isToggleInProgress) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("把手机代理分享给其他设备", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Text("完成授权后，依次开启代理共享和 Wi-Fi 热点。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            EmbeddedAdbProbePanel(onReady = { onAction(ShizukuAction.Refresh) })

            HotspotSectionCard {
                HotspotToggleHeader(R.drawable.ic_routing_24dp, "代理共享",
                    state.routingSessionEnabled, route.enabled) { onAction(ShizukuAction.ToggleRouting) }
                HotspotStateText(stringResource(route.statusRes), state.routingActive)
                Text(if (!state.vpnRunning && !state.routingSessionEnabled)
                    "请先返回首页，连接一个可用的代理节点。"
                    else "让热点设备使用手机当前的代理连接。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HotspotSectionCard {
                HotspotToggleHeader(R.drawable.ic_wifi_tethering_24dp, "Wi-Fi 热点",
                    state.hotspotEnabled, hotspot.enabled) { onAction(ShizukuAction.ToggleHotspot) }
                HotspotStateText(stringResource(hotspot.statusRes), state.hotspotEnabled)
                Text(if (state.hotspotEnabled) "其他设备连接此热点后，无需单独设置代理。"
                    else "先开启代理共享，再打开热点连接其他设备。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent("android.settings.TETHER_SETTINGS")) }
                }, contentPadding = PaddingValues(0.dp)) { Text("热点名称与密码") }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HotspotStep("1", "手机代理", state.vpnRunning, Modifier.weight(1f))
                HotspotStep("2", "代理共享", state.routingActive, Modifier.weight(1f))
                HotspotStep("3", "热点连接", state.hotspotEnabled, Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun HotspotSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun HotspotSectionHeader(iconRes: Int, title: String, status: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(23.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(50),
            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(status, style = MaterialTheme.typography.labelMedium,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun HotspotToggleHeader(iconRes: Int, title: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(23.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}

@Composable
private fun HotspotStateText(text: String, active: Boolean) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun HotspotStep(number: String, label: String, active: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = RoundedCornerShape(50),
            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(number, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
