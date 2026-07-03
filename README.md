# Hermes Agent (Android)

在 Android 上运行 [Hermes Agent](https://github.com/NousResearch/hermes-agent) 的独立 APK —— 无需 root，无需 PC，开箱即用。

通过 **proot + Ubuntu 24.04 rootfs** 在 Android 用户空间构建完整 Linux 环境，内置 hermes-web-ui Web 仪表盘。

## 当前版本：v0.0.1

> ⚠️ 早期预览版，功能可能不稳定，仅供测试。

## 架构

```
┌──────────────────────────────────────────────────┐
│                  Android APK                      │
│  com.nous.hermes.mobile                           │
│                                                   │
│  ┌───────────────┐    ┌──────────────────────┐    │
│  │  MainActivity │ →  │ proot (jniLibs)       │    │
│  │  (安装+仪表盘) │    │ libproot.so (~3MB)    │    │
│  └───────────────┘    └─────────┬────────────┘    │
│         ↑                        │                 │
│         │              ┌─────────▼────────────┐    │
│  ┌──────┴──────────────┤ Ubuntu 24.04 rootfs  │    │
│  │ app 数据目录         │ (~28MB 下载/解压)     │    │
│  │                     │                       │    │
│  │ ┌─────────────────┐ │ apt: python3, git,    │    │
│  │ │  rootfs/        │ │   build-essential,    │    │
│  │ │  (Ubuntu 24.04) │ │   libffi, openssl…    │    │
│  │ │                 │ │ pip: hermes-agent     │    │
│  │ │  bin/python3    │ │ npm: hermes-web-ui    │    │
│  │ │  bin/hermes     │ │                       │    │
│  │ └─────────────────┘ └───────────────────────┘    │
│  └────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

## 安装流程（首次启动）

应用内置 4 步安装向导，也可一键安装：

1. **proot** — 下载 Ubuntu 24.04 rootfs 并验证
2. **依赖** — `apt-get install` Python 3 + build-essential + libffi/openssl 等
3. **Hermes Agent** — `git clone` hermes-agent + 创建 venv + `pip install`
4. **WebUI** — `npm install -g hermes-web-ui`（含 Node.js 运行时）

首次安装需联网，总下载量约 1GB，耗时 10-30 分钟（取决于网络）。

## 功能

- **4 步安装向导** — proot / 依赖 / Hermes Agent / WebUI，支持单步安装或一键全装
- **Web 仪表盘** — 内置 hermes-web-ui，通过 WebView 直接访问，无需浏览器
- **服务启停** — 仪表盘独立控制 hermes-web-ui 服务启停
- **Web 通知** — 通过 JS 桥接将 Web Notifications API 映射到 Android 原生通知
- **环境备份/还原** — 导出/导入完整环境（rootfs + venv + 配置），换机不重装
- **在线更新** — 设置页一键更新 Hermes Agent（git pull + pip）和 WebUI（npm @latest）
- **实时日志** — 安装过程实时日志，支持当前/全部日志切换

## 系统要求

- Android 10（API 29）或更高
- `arm64-v8a` 架构（2017 年后绝大多数手机）
- 约 2GB 可用存储空间
- 首次安装需网络连接

## 构建

### 前置条件

- JDK 17
- Android SDK（compileSdk 35, build-tools 35.0.0）

### 构建 APK

```bash
# 下载 proot 二进制
./scripts/fetch-proot-binaries.sh

# Debug 构建
./gradlew assembleLiteDebug

# Release 构建（需签名配置）
export SIGNING_KEYSTORE_PATH=$HOME/hermes-release.jks
export SIGNING_KEYSTORE_PASSWORD=changeit
export SIGNING_KEY_ALIAS=hermes
export SIGNING_KEY_PASSWORD=changeit
./gradlew assembleLiteRelease
```

### CI 构建

推送到 `refactor/openclaw-rootfs` 分支或打 `v*` tag 时，GitHub Actions 自动构建 APK：

- 分支推送 → 滚动更新 `latest` pre-release
- `v*` tag → 创建正式 Release

```bash
git tag v0.0.1
git push origin v0.0.1
```

## 仓库结构

```
hermes-android-apk/
├── .github/workflows/build.yml          # CI 构建配置
├── app/
│   ├── build.gradle.kts                 # versionName, signing, abiFilters
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/nous/hermes/mobile/
│       │   ├── MainActivity.kt          # 安装向导 + 仪表盘 + 设置页
│       │   ├── ChatActivity.kt          # WebView + 通知桥接
│       │   ├── HermesServerManager.kt   # proot/rootfs/python/hermes 安装
│       │   ├── HermesStudioInstaller.kt # hermes-web-ui 安装 + 启停 + watchdog
│       │   ├── HermesForegroundService.kt
│       │   ├── ProotProcessManager.kt   # proot 进程管理
│       │   └── BootstrapManager.kt      # rootfs 下载/解压/路径管理
│       └── res/
│           ├── layout/activity_main.xml
│           └── values/strings.xml
├── scripts/fetch-proot-binaries.sh
└── README.md
```

## 致谢

- [Hermes Agent](https://github.com/NousResearch/hermes-agent) — Nous Research
- [AnyClaw](https://github.com/friuns2/openclaw-android-assistant) — 项目灵感来源
- [proot](https://github.com/proot-me/proot) — 用户空间 rootfs

## License

MIT
