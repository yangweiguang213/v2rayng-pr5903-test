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
update(manifest, 'android:label="@string/app_name"', 'android:label="v2rayNG Shell Test"')

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
        if (EmbeddedShellBinderProvider.binder() != null) return@withContext ShizukuStatus.READY
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
        if (!Shizuku.pingBinder() ||
''',
)
print("Applied embedded shell boot/service provider and core synchronization bridge.")
