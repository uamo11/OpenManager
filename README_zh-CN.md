<p align="center">
  <img src="art/launcher_icon.png" width="128" height="128" alt="OpenManager 图标" />
</p>

<h1 align="center">OpenManager</h1>
<p align="center"><b>适用于 Android 的开源双面板文件管理器</b></p>

<p align="center">
  <a href="https://github.com/uamo11/OpenManager/actions/workflows/android.yml"><img src="https://github.com/uamo11/OpenManager/actions/workflows/android.yml/badge.svg" alt="CI 状态" /></a>
  <a href="https://github.com/uamo11/OpenManager/releases"><img src="https://img.shields.io/github/v/release/uamo11/OpenManager?color=green&label=release" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-blue.svg" alt="GPLv3 许可证" /></a>
  <img src="https://img.shields.io/badge/Android-5.0%2B-brightgreen.svg?logo=android" alt="Android 5.0+" />
</p>

<p align="center">
  <a href="README.md">Español</a> · <a href="README_zh-CN.md">简体中文</a>
</p>

---

**OpenManager** 是一款适用于 Android 5.0+ 的现代化**双面板文件管理器**（MT Manager 风格），内置逆向工程套件（DEX / Smali 编辑器、HEX 编辑器、Apktool 反编译与重打包、APK 签名）、浮动音频播放器、网络客户端，并完整支持 **Root** 与 **SAF**。

---

## 📸 界面预览

<p align="center">
  <img src="screenshots/dual_panel_explorer.jpg" width="23%" alt="双面板文件管理器" />
  <img src="screenshots/navigation_drawer.jpg" width="23%" alt="侧边抽屉与工具" />
  <img src="screenshots/audio_player.jpg" width="23%" alt="内置音频播放器" />
  <img src="screenshots/menu_actions.jpg" width="23%" alt="快捷操作菜单" />
</p>

---

## ✨ 核心特性

### 🗂️ 1. 双面板文件浏览器
* **独立双面板：** 同时浏览两个不同目录，互不干扰。
* **快速操作：** 在左右面板间即时复制、移动与同步文件。
* **面包屑路径导航：** 快速跳转上级目录，实时显示目录项数与磁盘占用统计。
* **高效流转：** 快速切换面板、固定主目录，支持下载、SD 卡和内部存储一键直达。

### 🛠️ 2. 逆向工程与代码编辑
* **DEX / DEX Plus 编辑器：** 集成 baksmali/smali 引擎实时反编译和回编译 Smali，层级浏览包名、类、方法、字段和字符串池 (`string_ids`)，直接保存到 `.dex` 文件。
* **完整 HEX 编辑器：** 虚拟化极速滚动（每行 16 字节，含 Offset、HEX 和 ASCII），内置数据检查器（Int8/16/32 LE、Float32、二进制等），支持查找替换、撤回/重做、跳转偏移与校验和计算（MD5、SHA-1、SHA-256）。
* **APK 工具箱：** 查看 APK 详细信息，支持 **Apktool** 反编译与重编译、**V1 + V2 + V3** 签名与证书验证、提取 Split APKs (APKS) 以及 **Zipalign** 字节对齐优化。
* **应用图标查看器：** 高清预览应用图标，支持一键导出 PNG 至本地存储。
* **代码与文本编辑器：** 轻量级内置编辑器，支持语法高亮、行号显示和多种文件编码。

### 🎵 3. 多媒体与系统工具
* **浮动音频播放器：** 模态窗口播放音频，无需退出当前浏览界面；支持播放/暂停、快进/快退 10 秒、多倍速播放（1.0x–2.0x）及循环模式。
* **应用管理器 (App Manager)：** 列出用户与系统应用，支持按应用名或包名即时搜索、直达数据目录 (`/data/data/...`)、提取 APK 与快速卸载。
* **内置终端：** 一键在当前目录下启动本地 Shell 或 Termux。
* **远程服务与网络：** 内置 FTP 服务器便于与电脑局域网互传；支持 FTP、SFTP、SMB 和 WebDAV 客户端。

### 🔒 4. Root 与 SAF 支持
* **全面 Root 支持：** 深度集成 Magisk、KernelSU 和 APatch（基于 libsu），可无缝读写 `/`、`/data` 和 `/system` 等受保护分区。
* **自动化 SAF 访问：** 轻松读写高版本 Android 限制的 `Android/data` 与 `Android/obb` 目录。
* **无感权限体验：** 优先请求存储权限，按需申请通知权限，拒绝非必要权限时不产生阻断式弹窗。

---

## 🚀 从源码编译

环境要求：**JDK 21** 和已配置的 **Android SDK**。

```bash
git clone https://github.com/uamo11/OpenManager.git
cd OpenManager
./gradlew assembleDebug
# 生成的 Debug APK 位于: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📜 许可证

OpenManager 基于 **GNU General Public License v3.0** 开源。详情见 [LICENSE](LICENSE)。

```text
Copyright (C) 2018-2026 Hai Zhang, uamo11 与贡献者们。
```