# v2rayNG PR #5903：Shizuku 免 Root 热点代理测试版

此仓库通过 GitHub Actions 从 [v2rayNG PR #5903](https://github.com/2dust/v2rayNG/pull/5903) 的作者分支编译 Android **arm64-v8a** 测试 APK。

- **构建记录和 APK 下载**：[Actions](https://github.com/yangweiguang213/v2rayng-pr5903-test/actions)
- **编译用源码提交**：`eliotcougar/v2rayNG@9b5c6bf1be45c6b845190106910ad7d2617e75af`
- **构建方式**：GitHub Actions、Debug 签名、Play Store flavor、仅 ARM64
- **测试版包名**：`com.v2ray.ang.pr5903`（与常规包名不同）
- **GitHub Actions 产物保存**：7 天

## 在 Xiaomi 17 Pro / Android 16 上安装与测试

1. 打开上方 Actions，选择成功的构建，下载 `v2rayNG-PR5903-arm64-debug` artifact，解压得到 APK 并安装。
2. 安装并启动 [Shizuku](https://github.com/RikkaApps/Shizuku/releases)，通过 Android **无线调试**配对、启动服务。
3. 在测试版 v2rayNG 导入自己的节点并启用 **VPN 模式**；同一时刻只运行一个 Android VPN。
4. 打开侧边栏 **Tethering（网络共享）**，检查 Shizuku 状态并授权。
5. 启用 **Protected tethering（受保护共享）**，然后在该页面启动 Wi-Fi Hotspot。勿先用 Android 设置单独启动原生热点。
6. 连接热点的电脑不设置 HTTP/SOCKS 代理，运行 `curl -4 https://api.ipify.org` 检查出口 IP，继续验证 HTTPS、DNS 和 IPv6。

> ⚠️ 这是一个尚未正式合并的实验功能，没有 Xiaomi 17 Pro 的稳定性保证。验证前不要用于传输敏感数据。关闭测试共享后，务必到系统「个人热点」页面确认热点确已关闭；停止 VPN 不等于热点一定停止。

## 可能的故障

- **Shizuku 无法运行**：先连接普通 Wi-Fi，在开发者选项启用无线调试并重配对。
- **无法启动受保护共享**：检查 Shizuku 授权、v2rayNG 是否以 VPN 模式运行。HEV 后端还需要主核心提供可访问的本地 SOCKS inbound。
- **电脑连上热点但不能联网**：查看 Tethering 页显示的 testtun、上游及警告；仅成功连接 Wi-Fi 不代表流量已经通过 VPN。
- **构建失败**：打开 Actions 对应 job 的失败步骤，查看完整日志。仓库可直接修改工作流重新触发，也能从 Actions 手工运行。
- **Android 安装提示包冲突**：不要卸载原版；确认 APK 确由本仓库工作流生成并具有 `.pr5903` 测试包名。

原理和兼容性边界见 [PR 作者说明](https://github.com/eliotcougar/v2rayNG/blob/shizuku-tethering/SHIZUKU_TETHERING.md)。
