# v2rayNG 内置 Shell 热点实验版：Xiaomi 17 Pro 测试说明

> 这是 PR #5903 的**实验分支**，不是官方版本。即使 APK 编译成功，也不能代替 Xiaomi 17 Pro 实机验证。请勿提前卸载已经能用的 v2rayNG + Shizuku 环境。

## 安装

1. 从 [实验工作流 Actions](https://github.com/yangweiguang213/v2rayng-pr5903-test/actions/workflows/build-embedded-adb-spike.yml) 选择最近一次成功的运行，从 Artifacts 下载 `v2rayNG-embedded-shell-arm64-debug`。
2. 安装解压出的 APK。旧实验版若提示签名冲突，可仅卸载旧实验版后再安装（会清空实验版的配对密钥，需要重新配对）；不要卸载稳定版本。应用名称为 **v2rayNG 热点实验版**，包名为 `com.v2ray.ang.embedded`，与此前已经验证可用的 `com.v2ray.ang.pr5903` 不同。
3. 在实验版内导入有效节点并启动 VPN 模式。为确认不依赖外部 Shizuku，测试时先停止 Shizuku 服务（可以保留它已安装，不必卸载），不要同时运行两个 VPN。

## Xiaomi 17 Pro：优先通过通知栏完成配对

本版参考 Shizuku 的后台配对流程，避免用户切回 App 时 Android 的临时配对窗口关闭。

1. 打开「v2rayNG 热点实验版 → 网络共享」，先允许本应用通知权限，点击 **使用通知栏配对（推荐）**。
2. 进入系统「开发者选项 → 无线调试 → 使用配对码配对设备」，**停留在配对窗口**，不要点返回。
3. 下拉系统通知栏，找到「v2rayNG 无线调试配对」。发现服务后会出现「输入配对码」按钮；输入六位数字提交。
4. 收到「无线调试配对成功」通知后返回实验版，点击 **已经配对？直接启动**。
5. 若连接成功，再启用 v2rayNG VPN 和受保护热点，检查代理出口与 HTTPS、DNS 和断线保护。

注意：**配对成功和 Shell 服务启动是两个不同阶段**。本版增加前台 `app_process --probe` 自检：ADB Shell 身份通过后，会验证 Shell 能否读取 APK 并加载内部启动类。只有自检成功才正式启动网络共享服务。

### 发现失败的诊断

- `mDNS 广播=0`：本机没有发现配对/连接服务，需检查无线调试及系统弹出的配对窗口是否持续开启。
- `解析成功=0` 但发现了广播：属于系统服务解析/NSD 接口问题。
- `本机校验未通过` 增长：找到了广播，但解析地址没有匹配当前手机网络接口。
- 手动连接后提示 `ADB UID/DEX 均通过`：说明 ADB 与 APK 类加载已成功，失败在 Shell 服务初始化或 Binder。
- 提示 `Shell classloader 自检失败`：检查后面的输出，包括 `ClassNotFoundException` 或 `SecurityException`。
- 提示 `stage=...`：按指出的具体阶段判断是 `ActivityThread`、应用 Context、服务构造还是 Binder 注册失败。

### 更新测试版的注意事项

每次 GitHub Actions Debug 构建可能使用不同签名。安装时如提示签名冲突，只卸载 **com.v2ray.ang.embedded** 的旧实验版；不要卸载已经能正常使用的 `main` 稳定版。**卸载实验版会丢失本地 ADB 配对密钥，必须重新配对**，旧版“已配对”记录不能代替新安装版授权。

当前仍需要 Xiaomi 17 Pro 实机验证；本次编译成功不等于已确认摆脱独立 Shizuku 服务。

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
