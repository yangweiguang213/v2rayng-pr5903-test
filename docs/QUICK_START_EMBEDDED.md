# v2rayNG 内置 Shell 热点实验版：Xiaomi 17 Pro 测试说明

> 这是 PR #5903 的**实验分支**，不是官方版本。即使 APK 编译成功，也不能代替 Xiaomi 17 Pro 实机验证。请勿提前卸载已经能用的 v2rayNG + Shizuku 环境。

## 安装

1. 从 [实验工作流 Actions](https://github.com/yangweiguang213/v2rayng-pr5903-test/actions/workflows/build-embedded-adb-spike.yml) 选择最近一次成功的运行，从 Artifacts 下载 `v2rayNG-embedded-shell-arm64-debug`。
2. 安装解压出的 APK。应用名称为 **v2rayNG 热点实验版**，包名为 `com.v2ray.ang.embedded`，与此前已经验证可用的 `com.v2ray.ang.pr5903` 不同。
3. 在实验版内导入有效节点并启动 VPN 模式。为确认不依赖外部 Shizuku，测试时先停止 Shizuku 服务（可以保留它已安装，不必卸载），不要同时运行两个 VPN。

## 内置无线 ADB 配对（首次）

1. 手机先连接正常 Wi-Fi，开启「开发者选项 → 无线调试」。
2. 打开实验版 v2rayNG 左侧菜单的 **Tethering**。顶部会有「内置 ADB 权限测试（实验）」。
3. 在系统无线调试页面点击「使用配对码配对设备」，记录此页面的**配对端口**和六位配对码，填入实验版，点击「配对」。
4. 回到系统无线调试主页面，找到「IP 地址和端口」，把其中的**连接端口**填入实验版（不要与配对端口混淆）。主机保持默认的 `127.0.0.1`。
5. 点击「检查 Shell」，预期显示 `UID=2000`；再点击「启动内置 Shell 热点服务（实验）」。
6. 如果显示服务已注册，点击热点页下方的刷新，检查权限状态。接着打开 **Protected tethering**，再打开 **Wi-Fi hotspot**。

注意：配对成功只说明本机 ADB 身份已授权；`UID=2000` 只说明获得了 Shell 通道；仅当 **内置 Shell 服务已注册并且热点出口检测通过**时，才能认为不依赖 Shizuku 的共享已实现。

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
