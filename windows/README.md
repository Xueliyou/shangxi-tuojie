# 熵析拓解 · Windows 桌面版

和 Android 版**同一套界面**（`app/ui/index.html` 就是从 `assets/index.html` 来的），
原生层用 Node.js 重写，外面套一层 Electron。功能对齐 Android 版：

- 扫码登录 / Cookie 导入
- 搜索、歌单、专辑、榜单、歌曲详情
- 播放、进度、音量淡入淡出、歌词滚动（含中文翻译）
- 下载 MP3 / FLAC，写 ID3v2.3 / FLAC 标签（含封面、USLT、SYLT、`LYRICS`）
- 下载时自动合并 `tlyric`，生成同名 `.lrc`
- 深浅色主题、界面材质

## 目录

```
app/                 Electron 应用源码
  main.js            主进程：窗口、IPC、下载进度、剪贴板、外链
  preload.js         给页面注入 Native / StateBridge / DownloadBridge
  native/netease.js  网易云接口（weapi / eapi 加密，移植自 Android 的 NeteaseApi）
  native/tags.js     ID3v2.3 / FLAC 标签写入（移植自 TagWriter，字节级一致）
  native/download.js 下载、进度、写标签、生成 .lrc
  native/bridge.js   前端 call() 的路由层
  ui/                页面资源
installer.nsi        NSIS 安装脚本（用户级安装，不需要管理员）
build-win.sh         一键打包
icon.ico             安装包/快捷方式图标
```

## 构建

```bash
# 1) 下载 Electron（Windows x64）
wget https://npmmirror.com/mirrors/electron/45.0.0/electron-v45.0.0-win32-x64.zip
unzip electron-v45.0.0-win32-x64.zip -d electron

# 2) 需要 NSIS 3（Linux: apt install nsis；macOS: brew install makensis）

# 3) 打包
./build-win.sh
# → ShangxiTuojie-Setup-2.0.1.exe
```

产物是**用户级安装包**：装到 `%LOCALAPPDATA%\Programs\ShangxiTuojie`，
自动创建桌面 / 开始菜单快捷方式和卸载项，不需要管理员权限。

## 说明

- 首次打开没有登录态，请**扫码登录**（不要用验证码登录）。
- 下载默认保存到 `%USERPROFILE%\Music\<子目录>`，同名 `.lrc` 在旁边。
- 通知栏 / 媒体键这类 Android 专有能力，桌面版用窗口内控件替代；暂无系统托盘。
