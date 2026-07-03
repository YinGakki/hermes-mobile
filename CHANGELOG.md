# 更新日志

本项目所有重要改动都记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [v0.0.1] — 2026-07-03

首个版本号发布。基于 proot + Ubuntu 24.04 rootfs 架构，在 Android 上运行 Hermes Agent + WebUI 仪表盘。

### 新增

- **proot + rootfs 架构** — 通过 proot 在 Android 用户空间运行 Ubuntu 24.04 rootfs，无需 root
- **一键安装向导** — proot → 依赖 → Hermes Agent → WebUI 四步安装，支持一键全装
- **Web 仪表盘** — 内置 hermes-web-ui，通过 WebView 直接访问，无需额外浏览器
- **服务启停** — 仪表盘独立控制 hermes-web-ui 服务启停，带 watchdog 自动重启
- **Web 通知桥接** — 通过 JS 接口 + polyfill 将 Web Notifications API 映射到 Android 原生通知
- **环境备份/还原** — 导出/导入完整环境（rootfs + venv + 配置），换机不重装
- **在线更新** — 设置页一键更新 Hermes Agent（git pull + pip）和 WebUI（npm @latest）
- **实时日志** — 安装过程实时日志，支持当前/全部日志切换
- **前台服务** — 保活 hermes-web-ui 服务，含电池优化跳转
- **版本发布** — tag 驱动的 GitHub Release，APK 以版本号命名

### 技术细节

- minSdk 29 (Android 10)，targetSdk 28（绕过 W^X 限制，与 Termux 同策略）
- arm64-v8a only（proot 二进制 + Ubuntu rootfs 仅支持 aarch64）
- APK 约 14MB，首次安装下载约 1GB 依赖
- 镜像回退：packages.termux.dev → mirrors.tuna.tsinghua.edu.cn
- hermes-web-ui daemonize 模式 + 端口检测健康检查
- isRunning 状态缓存避免 UI 线程网络请求

---

[未发布]: 待定
