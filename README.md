# 熵析拓解（Shangxi Tuojie）

一个第三方的**网易云音乐 Android 客户端**：整个界面是一个加密进 APK 的 WebView 页面，
原生层只负责「网易云接口 / 音频播放 / 下载打标签 / 通知栏 / 系统交互」，
不用 Gradle、不引第三方库，一个 `build.sh` 就能出包。

<p align="center">
  <img src="res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="icon">
</p>

---

## ⚠️ 重要：请不要用验证码登录，请用扫码登录

> **登录时请选择「🔳 扫码登录」——用你手机上的网易云音乐 App 扫一下即可。**
>
> **不要用「📱 验证码登录」。** 短信验证码登录走的是非官方接口，很容易触发网易云的风控
> （登录失败、账号被临时限制、甚至需要短信二次校验），而且验证码这条链路很不稳定。
> 扫码登录跟官方客户端是同一套授权流程，**更安全、更稳，也不会给账号带来风险**。
>
> 仓库里保留了验证码登录的入口只是为了兼容旧版本，**强烈建议只用扫码登录**。

---

## 功能一览

### 音乐
- **搜索**：歌曲 / 歌单 / 专辑，支持分页
- **首页**：推荐歌单、榜单（含每个榜单的前三首预览）
- **收藏**：喜欢的歌单、专辑
- **歌曲**：歌曲详情、歌手、专辑、封面
- **歌词**：LRC 逐行滚动，支持逐字歌词（yrc/klyric 自动转标准 LRC）
- **音质**：标准 128K / 极高 320K / 无损 FLAC / Hi-Res / 高清环绕声 / 沉浸环绕声 / 超清母带 / 杜比全景声（取决于账号权限）

### 播放
- 内置播放器：播放/暂停、上一首/下一首、进度拖拽、音量淡入淡出（0 / 300 / 600 / 1200ms）
- 后台播放（前台服务 + MediaSession）
- 通知栏控制：播放/暂停/上下曲，封面、歌名、歌手、进度
- 播放页 / 歌词页双页滑动，歌词行聚焦与渐隐
- 60 / 90 / 120Hz 自适应，进度条走 `transform: scaleX()`，不在布局里抖动

### 下载
- 支持 MP3 / FLAC，按音质自动选格式
- **写标签**：标题、歌手、专辑、音轨号、封面、内嵌歌词
  - MP3（ID3v2.3）：`TIT2` / `TPE1` / `TALB` / `TRCK` / `APIC` / `USLT` / `TXXX:LYRICS` / **`SYLT`（逐行同步歌词）**
  - 无时间轴的歌词写 `TXXX:UNSYNCEDLYRICS`（纯文本）
  - FLAC：Vorbis Comment + `PICTURE`，歌词写 `LYRICS=` / `LYRIC=` / `UNSYNCEDLYRICS=`
- **中文翻译**：外语歌下载时自动把网易云的 `tlyric` 合并进歌词（原文一行、译文一行，同一时间戳），内嵌歌词和 `.lrc` 都带翻译
- **外挂歌词 `.lrc`**：与音频同名的 `.lrc` 写到 `Music/<子目录>/`
  - Android 11+ 需要「所有文件访问」权限才能直写；未授权时自动回退 MediaStore
- 下载进度、失败原因、取消，都走统一的任务面板

### 界面
- 深色 / 浅色主题，主题圆盘切换动画
- 三种界面材质、底部导航开关
- 启动页、封面模糊背景、迷你播放条
- 可打开内置网页层（自己填地址，App 不预置任何站点）
- 接收其它 App 分享过来的文本（自动识别歌曲/歌单/专辑链接）

### 登录
- **扫码登录（推荐）**：在 App 内拉起二维码，用网易云音乐 App 扫码确认
- Cookie 导入：手动粘贴 `MUSIC_U`
- 登录态保存在本地 `SharedPreferences`，可随时清除

> **公开版本不内置任何账号 Cookie**：首次打开请先用「扫码登录」授权，登录后才能搜索、播放和下载。

---

## 技术架构

```
┌──────────────────────────────────────────────┐
│  WebView（assets/index.html，AES-256-CBC 加密）│
│  · 全部 UI / 交互 / 动画 / 歌词渲染            │
└───────────────┬──────────────────────────────┘
                │  @JavascriptInterface  call(method, params, cb)
┌───────────────▼──────────────────────────────┐
│  ApiBridge（路由表）                          │
│  · 参数解析 / 线程切换 / 回调                  │
├───────────────┬──────────────────────────────┤
│ NeteaseApi    │ 网易云 eapi/weapi 封装        │
│ NeteaseLogin  │ 扫码登录 / 验证码登录         │
│ Downloader    │ 分块下载 / Range / 标签 / .lrc │
│ TagWriter     │ ID3v2.3 / FLAC 标签写入       │
│ MediaNotifier │ 通知栏 + MediaSession         │
│ Config        │ SharedPreferences 配置        │
└──────────────────────────────────────────────┘
```

- **零第三方依赖**：没有 OkHttp / Gson / Retrofit，HTTP、JSON、加解密全部用系统 API 手写
- **不用 Gradle**：`build.sh` 直接 `aapt2 + javac + R8 + zipalign + apksigner` 手工组包
- **前端加密**：`assets/index.html` 在构建时用 AES-256-CBC 加密成 `assets/payload.bin`，
  运行时由 `MainActivity` 解密后 `loadDataWithBaseURL` 加载，密钥在 `build/encrypt.js` 里
- **包体小**：release APK 约 500KB

---

## 下载 & 安装

到 [Releases](../../releases) 下载最新的 `熵析拓解-vX.Y.Z.apk` 安装即可。

- 最低支持 **Android 5.0（API 21）**，targetSdk 33
- 不需要 root，不需要 Xposed
- 首次使用建议：**扫码登录 → 设置里选默认音质 → 按需授权「所有文件访问」（为了外挂 .lrc）**
- 公开版不带任何默认 Cookie，不登录只能看到游客界面

---

## 电脑版（Windows）

电脑版已经**独立成仓库**：[Xueliyou/shangxi-tuojie-desktop](https://github.com/Xueliyou/shangxi-tuojie-desktop)

- 无边框桌面界面（侧边栏 / 播放条 / 歌词侧栏）
- 扫码登录、搜索、歌单 / 专辑 / 榜单、下载写标签与翻译歌词
- Windows 10 / 11 x64 安装包在电脑版仓库的 Releases 里

## 从源码构建

### 环境要求

| 工具 | 说明 |
|---|---|
| JDK 17 | `javac` / `keytool` |
| Android SDK | `android.jar`（API 34）+ `build-tools`（`d8`、`apksigner`、`zipalign`）|
| aapt2 | 资源编译（Ubuntu 的 `aapt2` 包即可）|
| Node.js | 构建时加密前端资源 |
| bash / zip | 组包 |

### 构建

```bash
# 工具都能在 PATH 里找到时：
bash build.sh

# 或者显式指定：
ANDROID_JAR=/path/to/android.jar \
D8=/path/to/d8 \
APKSIGNER=/path/to/apksigner \
ZIPALIGN=/path/to/zipalign \
bash build.sh
```

产物默认输出到**工程上一级目录**：`../熵析拓解-v<版本>.apk`。
首次构建会自动生成 `keystore.jks`（密码 `android`，仅用于本地调试，正式发布请换成自己的）。

### 版本号

版本号只在 `build.sh` 顶部维护：

```bash
APP_VER_CODE=117
APP_VER_NAME="1.10.6"
```

---

## 项目结构

```
.
├── build.sh                  # 一键构建脚本（aapt2 → javac → R8 → 对齐 → 签名）
├── AndroidManifest.xml
├── proguard-rules.pro        # 只保留入口类和 JS 接口，其余交给 R8
├── assets/
│   ├── index.html            # 前端（构建时会被加密成 payload.bin）
│   ├── webstate.js           # 网页状态记忆
│   └── app_icon.png
├── res/                      # 启动图标
└── src/com/whale/netease/
    ├── MainActivity.java     # WebView 宿主 / 权限 / 分享 / 返回键
    ├── ApiBridge.java        # JS ↔ 原生路由
    ├── NeteaseApi.java       # 网易云接口
    ├── NeteaseLogin.java     # 扫码登录 / 验证码登录
    ├── Downloader.java       # 下载 / Range 分块 / .lrc
    ├── TagWriter.java        # ID3v2.3 / FLAC 标签
    ├── MediaNotifier.java    # 通知栏 / MediaSession
    ├── MediaPlaybackService.java
    ├── DownloadService.java
    ├── Config.java
    ├── Crypto.java / Eapi.java / Weapi.java / Http.java
    └── ...
```

---

## 常见问题

**Q：为什么一直提示登录失败 / 要验证？**
A：多半是用了验证码登录。**换扫码登录**，或者手动填 Cookie。

**Q：下载的歌和歌词在哪？**
A：`内部存储/Music/<你设置的子目录>/`，同名 `.lrc` 在旁边。系统音乐 App 通常能直接扫到。

**Q：Android 11+ 没有生成 `.lrc`？**
A：到「设置 → 外挂歌词（.lrc）」里点「授权所有文件访问」，再重新下载一次。

**Q：为什么 APK 这么小？**
A：界面是 WebView，逻辑全在 `index.html` 里，原生层只做桥接；而且没引任何第三方库。

**Q：支持哪些音质？**
A：取决于账号权限。普通账号一般是 320K，黑胶 VIP 才有无损 / Hi-Res。

---

## 更新日志

### v2.0.1（2026-10-23）
- 新增 **Windows 桌面版**（Electron 移植，UI 与 Android 一致，支持扫码登录 / 播放 / 下载写标签 / 同步歌词）
- 修复：下载的外语歌没有中文翻译。下载时会一并取网易云的 `tlyric`，按时间戳合并进歌词，
  内嵌歌词（USLT / SYLT / FLAC `LYRICS=`）和同名 `.lrc` 现在都包含译文。

### v1.10.6
- 移除右下角「椒盐音乐」悬浮按钮及其未安装弹窗
- 公开版本不再内置任何账号 Cookie

## 免责声明

- 本项目**仅供学习交流**，用于研究 Android WebView、网易云接口封装、ID3/FLAC 标签写入等技术。
- 所有音乐、歌词、封面等内容的版权归**网易云音乐**及相应版权方所有，本项目不存储、不分发任何音频内容。
- 请勿将本项目用于任何商业用途；下载的内容请于 24 小时内删除。
- 使用本项目产生的任何后果（包括账号风险）由使用者自行承担。

---

## 致谢

- 接口思路参考：[Suxiaoqinx/Netease_url](https://github.com/Suxiaoqinx/Netease_url)（MIT）
- 虚拟屏 / 调试工具：[DSH](https://github.com/deepseek-ai)（本项目开发环境）

---

## 联系

- **QQ：3525848951**
- 有问题、建议、Bug 反馈，欢迎加 QQ 或在 Issues 里提。
- 再次提醒：**登录请用扫码，不要用验证码。**
