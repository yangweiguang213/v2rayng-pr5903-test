# v2rayNG 内置 Shell 热点实验版：Xiaomi 17 Pro 测试说明

> 这是 PR #5903 的**实验分支**，不是官方版本。即使 APK 编译成功，也不能代替 Xiaomi 17 Pro 实机验证。请勿提前卸载已经能用的 v2rayNG + Shizuku 环境。

## 安装

1. 从 [实验工作流 Actions](https://github.com/yangweiguang213/v2rayng-pr5903-test/actions/workflows/build-embedded-adb-spike.yml) 选择最近一次成功的运行，从 Artifacts 下载 `v2rayNG-embedded-shell-arm64-debug`。
2. 安装解压出的 APK。旧实验版若提示签名冲突，可仅卸载旧实验版后再安装（会清空实验版的配对密钥，需要重新配对）；不要卸载稳定版本。应用名称为 **v2rayNG 热点实验版**，包名为 `com.v2ray.ang.embedded`，与此前已经验证可用的 `com.v2ray.ang.pr5903` 不同。
3. 在实验版内导入有效节点并启动 VPN 模式。为确认不依赖外部 Shizuku，测试时先停止 Shizuku 服务（可以保留它已安装，不必卸载），不要同时运行两个 VPN。

## Xiaomi 17 Pro / Android 16：新版本机配对

新版采用类似 Shizuku 的本机 ADB mDNS 服务发现（`discoverServices + resolveService`），不再调用 Android 14+ 的持续服务信息回调，也不会主动调用需要用户选择设备的系统 picker。当前实验包面向 Android 16，目标 SDK 为 36。

1. 先连接 Wi-Fi，打开「开发者选项 → 无线调试」。
2. 进入「使用配对码配对设备」，记录六位配对码。这个窗口里的端口仅用于配对。
3. 进入实验版 v2rayNG →「网络共享」；界面会自动扫描本机两个无线调试端口。
4. 正常情况下无需选择任何系统网络设备，也不用手动填写 IP/端口。输入 **六位配对码**，点击「配对并启动」。
5. 已配对过且未更换应用密钥时，只需点击「已经配对？直接启动」。
6. 若发现接口异常，最多约九秒出现明确提示；点击「重新扫描」可重试。「高级设置」仅用于端口自动发现持续失败时的兜底操作。

### 截图中反复弹出“选择要连接的设备”

此弹窗来自旧实验版使用的 Android 17/Target SDK 37 局域网服务发现路径。新版本已换成更接近 Shizuku 的旧式一次性解析接口，并将目标 SDK 设为 36。**不要盲目反复点击该弹窗**；若新版仍出现，请记录弹窗截图及系统版本，先回退到已验证的稳定 APK。

### 如果 Shell 服务仍无法注册

新版会显示 `EmbeddedShellStarter` 的错误信息与 Shell 启动日志，便于区分类加载、权限、App Context 和 Binder Provider 问题。**它尚未完成 Xiaomi 17 Pro 实机运行验证**。实际热点共享成功的标准不是“配对成功”，而是内置 Shell 连接后，热点设备能访问 HTTPS 且出口符合 VPN 节点、VPN 断开不直连。

### 目前的交互限制

这版默认在 v2rayNG 页输入配对码；Shizuku 那种**在系统设置页面直接从通知输入配对码**的体验，还需要额外的通知输入服务。为避免再次引入前台服务与厂商后台兼容问题，本版先优先修复设备选择弹窗与闪退。

## 验证（建议使用电脑）

- 电脑连接手机热点，不安装代理客户端，不设置系统代理。
- Windows PowerShell 执行 `curl.exe -4 https://api.ipify.org`，比较它与 v2rayNG 节点出口 IP。
- 验证 HTTPS、DNS、IPv6；首次先关闭 IPv6，之后再逐项测试。
- 断开实验版 VPN，再用电脑访问网页。**不得直接回落到手机蜂窝网络公网出口**；热点应停止或保持无法联网。
- 测试完成后进入手机「个人热点」确认共享已经关闭，避免 HyperOS 3 的残留热点问题。

## 出错时

- 构建失败：查看 GitHub Actions 构建日志。
- 配对/连接失败：检查两个端口是否混淆；无线调试必须仍开启。改变 Wi-Fi 时 ADB 端口可能改变。
- `UID=2000` 正常但 Shell 服务没有注册：可在电脑 ADB 环境查看 `adb logcat -s EmbeddedShellStarter:* ShizukuTethering:*`。这通常说明 `app_process`、应用 Context 或 Binder Provider 启动链路存在问题，不代表 testtun 数据通道已启动。
- Shell 服务已注册但热点启动失败：查看 Tethering 页的 `testtun` 和上游警告，记录具体错误，暂时返回已验证的版本。

**重要限制**：Android Shell 权限不能像普通应用权限那样永久保存。重启系统后内置 Shell 进程通常消失，需要重新开启无线调试并启动服务；ADB 的配对身份保存在应用的私有、非云备份目录，通常无需重新配对，除非系统撤销了授权。
