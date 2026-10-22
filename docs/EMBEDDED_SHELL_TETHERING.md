# 单 APK / 免 Root 透明热点：最小特权服务设计

## 范围

目标：在 Xiaomi 17 Pro / Android 16 上，**只安装一份 v2rayNG APK**，不再单独安装 Shizuku 管理器或 Shizzi。保留已验证的 PR #5903 test-TUN → Xray/HEV → 热点逻辑，保留原有的 fail-closed 断线保护。

**不可消除的 Android 权限前提**：普通 App UID 无法直接使用 `TestNetworkManager`、`TetheringManager.setPreferTestNetworks()`。非 Root 版本仍必须通过 Android 无线调试取得 ADB Shell UID 2000。首次需要用户打开开发者选项和无线调试，输入配对码；手机重启后通常须重新启动特权服务。不得表述为无感永久提权。连接客户端不需要安装任何应用或设置代理。

## 拟保留的 PR #5903 逻辑

- 共享与普通主 VPN 使用的 profile/路由同步
- Android 管理 Wi-Fi/USB 的 DHCP/NAT/路由，不自行写 iptables
- Shell 端建立并发布 test-TUN，选为唯一可验证的热点上游
- 使用原有 HEV 或 native Xray TUN 数据通道
- 网络错误、代理重启、上游变化、权限丢失时执行 fail-closed
- 启用/关闭共享前后明确检查热点状态，尤其针对 HyperOS 3

## 最小特权模块（**不复制整个 Shizuku UI**）

1. **EmbeddedAdbBootstrap**（普通 App，`Kadb`）：本机无线调试配对、已配对设备重连和 Shell UID 自检；配对口令只保存在内存，绝不写日志。
2. **ShellTetheringAgent**（独立 Shell UID=2000 进程）：通过 ADB `app_process` 启动，加载内置 DEX/JNI，提供与 `IShizukuTetheringService` 兼容的 AIDL 接口。这个 Shell 进程是必要的，不是另一个需要安装的 APK。
3. **AuthenticatedBinderBridge**：Shell agent 使用仅限 Shell 调用的 app ContentProvider 将 Binder 交给自身普通进程；双方使用随机会话令牌、包名/UID 校验、Binder death watch，拒绝旧进程或重复会话。
4. **TetheringBackend**：原有 Shizuku backend 保留为可回退模式；新增 EmbeddedShell backend，**仅在 Shell agent 存活且经权限检查时**可控制 test-TUN 与热点。

## 安全约束

- 绝不在普通 App 进程直接尝试隐藏 API 来模拟特权调用。
- 不让 App 通过开放 HTTP/TCP 端口对外暴露通用 ADB Shell。
- 不把任意 Shell 命令执行接口暴露给热点客户端；仅保留受限制的共享管理 IPC。
- 使用 ADB TLS 配对，不禁用 ADB 身份验证，不要求系统 Root。
- 启动热点前必须先建立并验证 test-TUN；失去权限/接口时停止共享，不允许静默降级为直连。
- 启动的 shell 端用户服务在退出前释放所有 FD，检查保留 test network 的所有权；对系统未确认停止的共享保留保护网络。

## 当前完成状态

- [x] 创建独立 GitHub 分支，保留 main 可用版本不变
- [x] 引入内置 Kadb 配对及 Shell UID=2000 自检的原型 UI（仅在实验工作流构建）
- [ ] 完成 ShellTetheringAgent（test network/HEV/Xray 的现有 UserService 移植）
- [ ] 完成受保护的 Binder 传输与生命周期同步
- [ ] 将 Shizuku 切换为可选回退路径而非必装依赖
- [ ] Github Actions APK 编译验收及 Xiaomi 17 Pro 真实热点/DNS/IPv6/断网恢复验证

**警告：当前实验分支的 APK 尚不能独立实现不安装 Shizuku 的热点透明代理。此文档描述的目标不等于已完成交付。**

参考：
- https://github.com/2dust/v2rayNG/pull/5903
- https://github.com/eliotcougar/v2rayNG/blob/shizuku-tethering/SHIZUKU_TETHERING.md
- https://github.com/flyfishxu/Kadb
- https://github.com/RikkaApps/Shizuku-API
