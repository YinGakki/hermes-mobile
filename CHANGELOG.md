# 更新日志

本项目所有重要改动都记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [v0.0.2] — 2026-07-05

测试版。修复 v0.0.1 的多项问题，新增内置终端和应用图标。

### 新增

- **内置终端** — 打开终端按钮不再只显示说明，而是启动交互式 bash shell，在 proot rootfs 里直接运行 hermes 命令
- **应用图标** — 替换默认对勾图标为项目专属 H logo（深靛蓝渐变背景 + 青色 H + 电路节点 + 翼形装饰）
- **版本号显示** — 设置页更新卡片显示当前安装版本号
- **启动时自动检测更新** — Hermes Agent 用 git fetch 检查，WebUI 用 npm view 检查，有更新时绿色亮显 + badge
- **CI 版本号校验** — release job 打 tag 时校验 git tag 与 versionName 一致

### 修复

- **一键安装** — 4 个步骤按钮从 Button 改为 TextView，只有"一键安装"按钮可点击
- **安装 tab 动态显示** — 未安装只显示安装 tab，已安装隐藏安装 tab 只显示仪表盘+设置
- **步骤状态显示** — 正在安装的步骤不会被错误标记为"已完成"，完成文案改为步骤专属（"✓ Hermes Agent 已完成"等）
- **全部日志显示** — fullLog 容量从 5000 提升到 50000 行，超长时显示最后 2000 行
- **弹窗宽度自适应** — 从 85%/280-520dp 改为 92%/320-720dp
- **聊天页退出** — 右上角悬浮 ✕ 按钮直接退出回控制台
- **停止服务崩溃** — stop() 用独立线程 + 10s 超时，避免卡死或崩溃
- **deb bundle 日志移除** — 删除不再使用的 extractDebBundleIfPresent 方法
- **架构图错位** — 简化为单嵌套框，去除 CJK 字符对齐问题
- **默认分支改为 main** — 配合分支重命名

### 技术细节

- versionCode: 1 → 2
- 新增 TerminalActivity.kt（内置终端）
- 新增 update_badge_bg.xml（更新 badge 背景）
- 新增 ic_launcher_background/foreground 矢量图（自适应图标）
- 新增 getHermesVersion/checkHermesUpdate/getWebUIVersion/checkWebUIUpdate 方法

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
