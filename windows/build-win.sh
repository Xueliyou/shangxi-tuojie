#!/bin/bash
# 构建 Windows 安装包（在 Linux / macOS 上也能跑，不需要 Windows）
# 依赖：makensis（NSIS 3）、bash、cp；Electron 运行时需自行下载 win32-x64 版
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
STAGE="$ROOT/stage"

if [ ! -x "$ROOT/electron/electron.exe" ]; then
  echo "缺少 Electron：把 electron-v45.0.0-win32-x64.zip 解压到 $ROOT/electron/ 后再跑"
  echo "下载：https://npmmirror.com/mirrors/electron/45.0.0/electron-v45.0.0-win32-x64.zip"
  exit 1
fi
command -v makensis >/dev/null || { echo "缺少 makensis（apt install nsis）"; exit 1; }

rm -rf "$STAGE"
mkdir -p "$STAGE/resources/app"
cp -a "$ROOT/electron/." "$STAGE/"
rm -f "$STAGE/resources/default_app.asar"
cp -a "$ROOT/app/." "$STAGE/resources/app/"
if [ -d "$STAGE/locales" ]; then
  find "$STAGE/locales" -type f ! -name "zh-CN.pak" ! -name "en-US.pak" -delete
fi
mv "$STAGE/electron.exe" "$STAGE/ShangxiTuojie.exe"

cd "$ROOT"
makensis -V2 installer.nsi
ls -lh "$ROOT"/ShangxiTuojie-Setup-*.exe
