#!/usr/bin/env python3
"""Apply the isolated Shizuku-free, shell-UID transport experiment to PR5903."""
from pathlib import Path

root = Path("V2rayNG/app/src/main")
java = root / "java/com/v2ray/ang"
overlay = Path("integration/overlay/V2rayNG/app/src/main/java/com/v2ray/ang")

for source in (overlay / "shizuku").glob("EmbeddedShell*.kt"):
    target = java / "shizuku" / source.name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(source.read_bytes())

def update(path: Path, old: str, new: str) -> None:
    original = path.read_text()
    assert original.count(old) == 1, f"Expected exactly one match in {path}: {old[:90]}"
    path.write_text(original.replace(old, new, 1))

manifest = root / "AndroidManifest.xml"
update(manifest, 'android:icon="@mipmap/ic_launcher"\n        android:label="@string/app_name"', 'android:icon="@mipmap/ic_launcher"\n        android:label="v2rayNG 热点实验版"')

update(
    manifest,
    '        <activity\n            android:name=".ui.ShizukuActivity"',
    '''        <!-- Shell UID process sends its Binder only through this guarded provider. -->
        <provider
            android:name=".shizuku.EmbeddedShellBinderProvider"
            android:authorities="${applicationId}.embedded-shell"
            android:exported="true" />
        <activity
            android:name=".ui.ShizukuActivity"''',
)

viewmodel = java / "ui/ShizukuViewModel.kt"
update(viewmodel, "import com.v2ray.ang.shizuku.HotspotRoutingConfig",
       "import com.v2ray.ang.shizuku.EmbeddedShellBinderProvider\nimport com.v2ray.ang.shizuku.HotspotRoutingConfig")
update(
    viewmodel,
    '    private suspend fun getShizukuStatus(): ShizukuStatus = withContext(Dispatchers.IO) {\n',
    '''    private suspend fun getShizukuStatus(): ShizukuStatus = withContext(Dispatchers.IO) {
        return@withContext if (EmbeddedShellBinderProvider.binder() != null)
            ShizukuStatus.READY else ShizukuStatus.NOT_RUNNING
''',
)
update(
    viewmodel,
    '''    private fun bindUserService() {
        if (_uiState.value.operation == TetheringOperation.CONNECTING || tetheringService != null) return
''',
    '''    private fun bindUserService() {
        if (_uiState.value.operation == TetheringOperation.CONNECTING || tetheringService != null) return
        EmbeddedShellBinderProvider.binder()?.let {
            userServiceConnection.onServiceConnected(
                ComponentName(app, ShizukuTetheringService::class.java), it,
            )
            return
        }
        // Experimental single-APK build never silently falls back to external Shizuku.
        return
''',
)
update(
    viewmodel,
    '                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, false)\n            }.onFailure { logUiFailure("cleanup"',
    '                if (EmbeddedShellBinderProvider.binder() == null)\n                    Shizuku.unbindUserService(userServiceArgs, userServiceConnection, false)\n            }.onFailure { logUiFailure("cleanup"',
)

receiver = java / "shizuku/ShizukuRoutingSyncReceiver.kt"
update(receiver, "import com.v2ray.ang.AppConfig", "import com.v2ray.ang.AppConfig")
update(
    receiver,
    '''    private fun bindIfNeeded() {
        if (binding) return
        if (!Shizuku.pingBinder() ||
''',
    '''    private fun bindIfNeeded() {
        if (binding) return
        EmbeddedShellBinderProvider.service()?.let {
            service = it
            pump()
            return
        }
        failAll("Embedded Shell service unavailable; no external Shizuku fallback")
        return
        if (!Shizuku.pingBinder() ||
''',
)
print("Applied embedded shell boot/service provider and core synchronization bridge.")

# Give users an accurate status: this experiment does not use a Shizuku manager.
strings = root / "res/values-zh-rCN/strings.xml"
labels = {
    '<string name="shizuku_status_title">Shizuku 状态</string>':
        '<string name="shizuku_status_title">内置 Shell 服务状态</string>',
    '<string name="shizuku_status_not_installed">未安装 Shizuku</string>':
        '<string name="shizuku_status_not_installed">内置服务尚未启动</string>',
    '<string name="shizuku_status_not_running">Shizuku 未运行</string>':
        '<string name="shizuku_status_not_running">内置服务尚未启动</string>',
}
for before, after in labels.items():
    update(strings, before, after)

core_sync = java / "shizuku/TetheringCoreSync.kt"
update(core_sync, "                watchShizuku(service)\n", "                // Embedded backend does not require a Shizuku manager.\n")

# Keep the experimental path explicit: no hidden manager fallback or misleading
# READY state when an external Shizuku happens to be installed.
v = viewmodel.read_text()
def replace_section(data, start_marker, end_marker, replacement):
    i = data.index(start_marker)
    j = data.index(end_marker, i)
    return data[:i] + replacement + data[j:]

v = replace_section(
    v,
    '    private suspend fun getShizukuStatus(): ShizukuStatus = withContext(Dispatchers.IO) {',
    '    private fun requestShizukuPermission()',
    '''    private suspend fun getShizukuStatus(): ShizukuStatus = withContext(Dispatchers.IO) {
        if (EmbeddedShellBinderProvider.binder() != null)
            ShizukuStatus.READY else ShizukuStatus.NOT_RUNNING
    }

''',
)
v = replace_section(
    v,
    '    private fun bindUserService() {',
    '    private fun refreshTetheringStatus()',
    '''    private fun bindUserService() {
        if (tetheringService != null) return
        val binder = EmbeddedShellBinderProvider.binder() ?: return
        userServiceConnection.onServiceConnected(
            ComponentName(app, ShizukuTetheringService::class.java), binder
        )
    }

''',
)
viewmodel.write_text(v)

r = receiver.read_text()
r = replace_section(
    r,
    '    private fun bindIfNeeded() {',
    '    private fun forward(',
    '''    private fun bindIfNeeded() {
        val embedded = EmbeddedShellBinderProvider.service()
        if (embedded == null) {
            failAll("Embedded shell service not running")
            return
        }
        service = embedded
        pump()
    }

''',
)
receiver.write_text(r)

# Help Android NSD discover wireless debugging _adb-tls-pairing and _adb-tls-connect.
update(
    manifest,
    '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
    '''    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />''',
)

# Single-APK mode: the original Shizuku permission buttons cannot work.
# Put pairing first, then show the actual Binder status and routing switches.
screen = java / "ui/TetheringScreen.kt"
ui = screen.read_text()
begin = ui.index('            Row(\n                modifier = Modifier\n', ui.index('title = stringResource(R.string.shizuku_status_title)'))
end = ui.index('            Spacer(modifier = Modifier.height(8.dp))', begin)
ui = ui[:begin] + ui[end:]
panel = '            EmbeddedAdbProbePanel(onReady = { onAction(ShizukuAction.Refresh) })\n'
assert ui.count(panel) == 1
ui = ui.replace(panel, '')
column_anchor = '                .verticalScroll(rememberScrollState()),\n        ) {\n'
assert ui.count(column_anchor) == 1
ui = ui.replace(column_anchor, column_anchor + panel, 1)
screen.write_text(ui)

# Do not subscribe to Shizuku manager lifecycle events in the self-contained APK.
v = viewmodel.read_text()
for obsolete in (
    '        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)\n',
    '        Shizuku.addBinderDeadListener(binderDeadListener)\n',
    '        Shizuku.addRequestPermissionResultListener(permissionResultListener)\n',
):
    assert v.count(obsolete) == 1, obsolete
    v = v.replace(obsolete, '', 1)
viewmodel.write_text(v)

update(
    strings,
    '<string name="shizuku_status_open_manager">请安装或启动 Shizuku，然后刷新此页面。</string>',
    '<string name="shizuku_status_open_manager">请先在上方完成本机无线调试授权并启动内置 Shell 服务。</string>',
)
