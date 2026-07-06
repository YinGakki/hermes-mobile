# 更新日志

本项目所有重要改动都记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [v0.0.2] — 2026-07-05

测试版。修复 v0.0.1 的多项问题，新增 Termux 风格 PTY 终端、应用图标和中文化通知。

### 新增

- **PTY 终端** — 内置终端从 ProcessBuilder 管道升级为真正的伪终端（PTY），支持交互式 TUI 程序（hermes setup 向导、vim、htop 等）；界面改为 Termux 风格全屏终端，直接在终端内输入（无独立输入框）；底部功能键栏（ESC、Tab、Ctrl、方向键）适配软键盘；ANSI 转义序列处理（剥离颜色、\r 行覆盖）；支持 Ctrl+C / Ctrl+D 等控制字符
- **应用图标** — 替换默认对勾图标为项目专属 H logo：深空渐变背景（深石板蓝 → 深青）+ 青色 H 字母主体 + 双侧三层 Hermes 信使翅膀（渐变羽毛）+ 横杠中央终端提示符 `>_` + 四角发光节点（AI 神经网络意象）
- **版本号显示** — 设置页更新卡片显示当前安装版本号
- **启动时自动检测更新** — Hermes Agent 用 git fetch 检查，WebUI 用 npm view 检查，有更新时绿色亮显 + badge
- **CI 版本号校验** — release job 打 tag 时校验 git tag 与 versionName 一致
- **CI 自动提取更新日志** — release job 从 CHANGELOG.md 自动提取对应版本内容作为 Release body
- **APK 自更新检测** — 每次启动后台检查 GitHub Releases 是否有新版本，有更新时发通知提醒；设置页新增 APK 更新卡片，显示当前版本→最新版本
- **更新通道选择** — 支持选择"正式版"或"测试版"通道：正式版只检查非 prerelease，测试版包含 prerelease（基于 GitHub Release 的 prerelease 标志）
- **更新日志对话框** — 点击 APK 更新卡片显示发布页面的更新日志（Release body），去除 markdown 标记后以纯文本展示，包含版本号、通道、文件大小信息
- **APK 下载安装** — 对话框点击"下载更新"后后台下载 APK（带进度百分比），下载完成自动触发系统安装界面；使用 FileProvider 共享文件，需 REQUEST_INSTALL_PACKAGES 权限

### 修复

- **一键安装** — 4 个步骤按钮从 Button 改为 TextView，只有"一键安装"按钮可点击
- **安装 tab 动态显示** — 未安装只显示安装 tab，已安装隐藏安装 tab 只显示仪表盘+设置
- **步骤状态显示** — 正在安装的步骤不会被错误标记为"已完成"，完成文案改为步骤专属（"✓ Hermes Agent 已完成"等）
- **全部日志显示** — fullLog 容量从 5000 提升到 50000 行，超长时显示最后 2000 行
- **弹窗宽度自适应** — 从 85%/280-520dp 改为 92%/320-720dp
- **聊天页退出方式优化** — 移除悬浮按钮，改为左边缘滑动手势退出：从屏幕左边缘向右滑动显示跟随手指的"← 退出"指示器，滑动超过 100dp 松手即退出，未超过则回弹隐藏；首次进入 Toast 提示手势；界面完全干净无常驻按钮
- **设置页重新安装按钮** — 修复点击无反应问题：原逻辑调用 extractBootstrap 后被 refreshNavTabs 弹回仪表盘；改为直接显示安装界面（4 步骤 + 日志面板），用户可重新点击"一键安装"
- **仪表盘重新安装按钮** — 移除仪表盘的"重新安装环境"入口，统一由设置页的"重新安装"按钮触发
- **检查更新卡片简化** — 副标题移除更新命令文本（git pull/npm install），改为项目说明（"Hermes Agent 核心服务"/"WebUI 仪表盘界面"）；只显示当前版本号 + 有无更新状态
- **停止服务崩溃** — stop() 用独立线程 + 10s 超时，避免卡死或崩溃
- **deb bundle 日志移除** — 删除不再使用的 extractDebBundleIfPresent 方法
- **架构图错位** — 简化为单嵌套框，去除 CJK 字符对齐问题
- **默认分支改为 main** — 配合分支重命名
- **通知中文化** — 前台服务通知（渠道名、描述、标题、内容）从英文改为中文显示

### 技术细节

- versionCode: 1 → 2
- 新增 TerminalActivity.kt（内置终端）
- 新增 update_badge_bg.xml（更新 badge 背景）
- 新增 ic_launcher_background/foreground 矢量图（自适应图标，含翅膀/终端符/发光节点）
- 新增 getHermesVersion/checkHermesUpdate/getWebUIVersion/checkWebUIUpdate 方法
- 新增 JNI PTY 桥接：app/src/main/cpp/pty.c + CMakeLists.txt → libhermespty.so
- 新增 PtyNative.kt（JNI 绑定：createSubprocess/read/write/setWindowSize/waitFor/close/killProcess）
- 新增 TerminalView.kt（自定义终端视图：ANSI 剥离、\r 行覆盖、字符级输入、InputConnection 捕获软键盘）
- TerminalActivity 改为 PTY 终端：posix_openpt + fork + exec proot + bash，替代 ProcessBuilder 管道
- ChatActivity 新增 SwipeExitContainer 内部类（边缘滑动手势 + 跟随手指指示器）
- HermesForegroundService 通知文本硬编码改为中文字面量
- build.gradle.kts 新增 externalNativeBuild（CMake）+ ndkVersion 27.0.12077973
- CI 新增 NDK + CMake SDK 包安装
- 新增 ApkUpdateChecker.kt（GitHub Releases API 检测 APK 更新，支持正式/测试版通道 + ghproxy 代理加速）
- 新增 res/xml/file_paths.xml（FileProvider 路径配置，用于 APK 安装）
- AndroidManifest 新增 REQUEST_INSTALL_PACKAGES 权限 + FileProvider 声明
- MainActivity 新增 APK 更新检测/更新日志对话框/下载安装/后台通知方法
- 更新偏好存储于 hermes_prefs（update_channel 键）

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
