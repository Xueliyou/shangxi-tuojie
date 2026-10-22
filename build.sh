#!/bin/bash
# ============================================================
#  熵析拓解 —— 手工构建脚本（不依赖 Gradle / Android Studio）
#  用法：bash build.sh
#
#  工具链全部自动探测，也可以用环境变量指定：
#    JAVA_HOME  ANDROID_JAR  ANDROID_BUILD_TOOLS
#    AAPT2  AAPT  D8  R8_JAR  APKSIGNER  ZIPALIGN
#  资源编译器二选一：有 aapt2 用 aapt2（快），没有就退回 aapt v1。
#  探测不到的会直接报错退出，不会产出残缺 APK。
# ============================================================
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJ"

# ---------------- 元信息 ----------------
APP_VER_CODE=117
APP_VER_NAME="1.10.6"
MIN_SDK=21
TARGET_SDK=33
APP_NAME="熵析拓解"

OUT="$PROJ/build"
APK_UNSIGNED="$OUT/unsigned.apk"
APK_ALIGNED="$OUT/aligned.apk"
# 安装包统一输出到工程上一级（/sdcard/dsh/netease-app/），和旧版本 APK 放在一起
APK_DIR="$(cd "$PROJ/.." && pwd)"
APK_FINAL="$APK_DIR/${APP_NAME}-v${APP_VER_NAME}.apk"

say() { printf '\033[36m[build]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[fail ]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------- 工具链探测 ----------------
[ -x /opt/jdk17/bin/javac ] && export PATH="/opt/jdk17/bin:$PATH"
command -v javac >/dev/null 2>&1 || die "找不到 javac（需要 JDK 17+）"
command -v java  >/dev/null 2>&1 || die "找不到 java（需要 JDK 17+）"
command -v node  >/dev/null 2>&1 || die "找不到 node（用于加密前端资源）"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT_DIR=""
for d in "${ANDROID_BUILD_TOOLS:-}" "$SDK/build-tools/34.0.0" \
         /root/tools/bt/android-14 /opt/android-sdk/build-tools/34.0.0 \
         /usr/lib/android-sdk/build-tools/34.0.0; do
    if [ -n "$d" ] && [ -e "$d/d8" ]; then BT_DIR="$d"; break; fi
done
[ -n "$BT_DIR" ] || die "找不到 Android build-tools（需要 d8 / apksigner）"

D8="${D8:-$BT_DIR/d8}"
# 签名与对齐优先用系统里的（可能是本地编译的 arm64 版本），再退回 build-tools
APKSIGNER="${APKSIGNER:-$(command -v apksigner || true)}"
[ -n "$APKSIGNER" ] || APKSIGNER="$BT_DIR/apksigner"
ZIPALIGN="${ZIPALIGN:-$(command -v zipalign || true)}"
[ -n "$ZIPALIGN" ] || ZIPALIGN="$BT_DIR/zipalign"

# 资源编译器：aapt2 优先，其次 aapt v1（Ubuntu 的 aapt 包只带 v1）
can_run() { [ -n "$1" ] && [ -x "$1" ] && "$1" version >/dev/null 2>&1; }
AAPT2_BIN=""
for p in "${AAPT2:-}" /usr/bin/aapt2 "$BT_DIR/aapt2"; do
    if can_run "$p"; then AAPT2_BIN="$p"; break; fi
done
AAPT_BIN=""
for p in "${AAPT:-}" "$(command -v aapt || true)" /usr/bin/aapt; do
    if can_run "$p"; then AAPT_BIN="$p"; break; fi
done
[ -n "$AAPT2_BIN" ] || [ -n "$AAPT_BIN" ] || die "找不到可用的 aapt2 / aapt"

if [ -z "${ANDROID_JAR:-}" ]; then
    for p in "$SDK/platforms/android-34/android.jar" \
             /root/tools/pf/android-34/android.jar \
             /opt/android-sdk/platforms/android-34/android.jar \
             /opt/fpsmon-tools/x/android-35/android.jar; do
        [ -f "$p" ] && { ANDROID_JAR="$p"; break; }
    done
fi
[ -f "${ANDROID_JAR:-}" ] || die "找不到 android.jar（用 ANDROID_JAR=... 指定）"

R8_JAR="${R8_JAR:-}"
if [ -z "$R8_JAR" ]; then
    for p in /root/tools/r8.jar "$BT_DIR/lib/r8.jar" \
             /opt/fpsmon-tools/x/cmdline-tools/lib/r8.jar; do
        [ -f "$p" ] && { R8_JAR="$p"; break; }
    done
fi

for f in "$APKSIGNER" "$ZIPALIGN" "$D8"; do
    [ -e "$f" ] || die "缺少工具: $f"
done

# ---------------- 签名 ----------------
KEYSTORE="$PROJ/keystore.jks"
KS_ALIAS="whalefm"
KS_PASS="android"

say "工具链：aapt2=${AAPT2_BIN:-（无）}  aapt=${AAPT_BIN:-（无）}"
say "        android.jar=$ANDROID_JAR"
say "        R8=${R8_JAR:-（未找到，回退 d8 不混淆）}"

# ---------------- 图标自愈 ----------------
# /sdcard 上的 PNG 会被系统清理机制"回收"（重命名成 .trashed-*），
# 构建前自动从容器内的备份恢复，避免又出一次"图标资源找不到"的构建失败。
ICON_BAK="/root/netease-icons"
if [ -d "$ICON_BAK" ]; then
    for d in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
        bak="$ICON_BAK/mipmap-$d.png"
        dst="$PROJ/res/mipmap-$d/ic_launcher.png"
        if [ -f "$bak" ] && [ ! -f "$dst" ]; then
            mkdir -p "$PROJ/res/mipmap-$d"
            cp "$bak" "$dst"
            say "图标缺失 → 已从备份恢复 mipmap-$d"
        fi
    done
fi
for d in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    [ -f "$PROJ/res/mipmap-$d/ic_launcher.png" ] \
        || die "缺少图标 res/mipmap-$d/ic_launcher.png（备份在 $ICON_BAK，可手动复制回来）"
done

if [ -f "$ICON_BAK/app_icon.png" ] && [ ! -f "$PROJ/assets/app_icon.png" ]; then
    cp "$ICON_BAK/app_icon.png" "$PROJ/assets/app_icon.png"
    say "assets/app_icon.png 缺失 → 已从备份恢复"
fi
[ -f "$PROJ/assets/app_icon.png" ] \
    || die "缺少 assets/app_icon.png（备份在 $ICON_BAK/app_icon.png）"

[ -f assets/index.html ] || die "缺少 assets/index.html"

# ---------------- 清场 ----------------
say "清理 build/"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# ---------------- 前端加密 ----------------
say "加密前端资源（AES-256-CBC → assets/payload.bin）"
rm -rf "$OUT/assets"
mkdir -p "$OUT/assets"
cp -r assets/. "$OUT/assets/"
rm -f "$OUT/assets"/.trashed-* "$OUT/assets"/payload.bin

cat > "$OUT/encrypt.js" <<'JSEOF'
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const dir = process.argv[2];
const src = path.join(dir, 'index.html');
if (!fs.existsSync(src)) { console.log('  [warn] 没有 assets/index.html，跳过加密'); process.exit(0); }
const key = Buffer.from('7a1e532b9c40d7660fb2358a51e429c86d03971caf582ed144860be93fc5729d', 'hex');
const iv = crypto.randomBytes(16);
const cipher = crypto.createCipheriv('aes-256-cbc', key, iv);
const enc = Buffer.concat([iv, cipher.update(fs.readFileSync(src)), cipher.final()]);
fs.writeFileSync(path.join(dir, 'payload.bin'), enc);
fs.unlinkSync(src);
console.log('  payload.bin:', enc.length, 'bytes');
JSEOF
node "$OUT/encrypt.js" "$OUT/assets"

# ---------------- 资源 / 组包 ----------------
# 版本号只在 build.sh 里维护一处：把它注入清单副本，
# 这样 aapt2 和 aapt v1（不一定支持 --version-code）两条路都能用
say "生成清单副本（versionCode=$APP_VER_CODE / versionName=$APP_VER_NAME）"
sed -E "s#(package=\"[^\"]*\")>#\1\n    android:versionCode=\"$APP_VER_CODE\"\n    android:versionName=\"$APP_VER_NAME\">#" \
    AndroidManifest.xml > "$OUT/AndroidManifest.xml"
grep -q "android:versionCode=\"$APP_VER_CODE\"" "$OUT/AndroidManifest.xml" \
    || die "清单注入版本号失败"

if [ -n "$AAPT2_BIN" ]; then
    say "aapt2 compile：编译 res/"
    "$AAPT2_BIN" compile --dir res -o "$OUT/res.zip"

    say "aapt2 link：生成基础 APK（含加密 assets）"
    "$AAPT2_BIN" link \
        -o "$APK_UNSIGNED" \
        -I "$ANDROID_JAR" \
        --manifest "$OUT/AndroidManifest.xml" \
        "$OUT/res.zip" \
        -A "$OUT/assets" \
        --java "$OUT/gen" \
        --min-sdk-version "$MIN_SDK" \
        --target-sdk-version "$TARGET_SDK"
else
    say "aapt（v1）：编译资源 + 生成基础 APK（含加密 assets）"
    "$AAPT_BIN" package -f -m \
        -J "$OUT/gen" \
        -M "$OUT/AndroidManifest.xml" \
        -S res \
        -A "$OUT/assets" \
        -I "$ANDROID_JAR" \
        --min-sdk-version "$MIN_SDK" \
        --target-sdk-version "$TARGET_SDK" \
        -F "$APK_UNSIGNED"
fi

# ---------------- 编译 ----------------
say "javac：编译 Java 源码"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
set +e
javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
JAVAC_RC=$?
set -e
grep -v "^Note:" "$OUT/javac.log" || true
[ "$JAVAC_RC" -eq 0 ] || die "javac 编译失败（见上面报错），已中止，不会产出残缺 APK"

# 逐个核对源文件都生成了 class，防止"部分编译成功"蒙混过关
MISSING=""
while read -r f; do
    if [[ "$f" == src/* ]]; then
        rel="${f#src/}"
    else
        rel="${f#"$OUT/gen/"}"
    fi
    cls="$OUT/classes/${rel%.java}.class"
    [ -f "$cls" ] || MISSING="$MISSING $f"
done < "$OUT/sources.txt"
[ -z "$MISSING" ] || die "以下源文件没有生成 class：$MISSING"
say "编译产物校验通过（$(find "$OUT/classes" -name '*.class' | wc -l) 个 class）"

# ---------------- 混淆 / dex ----------------
if [ -n "$R8_JAR" ]; then
    say "R8：混淆 + 生成 dex"
    java -cp "$R8_JAR" com.android.tools.r8.R8 \
        --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
        --pg-conf "$PROJ/proguard-rules.pro" \
        --output "$OUT/dex" \
        $(find "$OUT/classes" -name '*.class') || die "R8 混淆失败"
    say "已混淆（类名 / 成员名已重命名）"
else
    say "未找到 R8，回退 d8（无混淆）"
    "$D8" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
        --output "$OUT/dex" \
        $(find "$OUT/classes" -name '*.class') || die "d8 生成 dex 失败"
fi

# ---------------- 塞 dex ----------------
say "把 classes.dex 塞进 APK"
if command -v zip >/dev/null 2>&1; then
    (cd "$OUT/dex" && zip -q -X "$APK_UNSIGNED" classes.dex)
elif command -v jar >/dev/null 2>&1; then
    (cd "$OUT/dex" && jar uf "$APK_UNSIGNED" classes.dex)
else
    die "没有 zip 也没有 jar，无法把 classes.dex 放进 APK"
fi

# ---------------- 对齐 / 签名 ----------------
say "zipalign"
"$ZIPALIGN" -f 4 "$APK_UNSIGNED" "$APK_ALIGNED"

if [ ! -f "$KEYSTORE" ]; then
    say "首次构建：生成签名密钥 keystore.jks"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=NeteaseWhale, OU=DSH, O=Whale, L=Beijing, ST=Beijing, C=CN" \
        >/dev/null 2>&1
fi

say "apksigner：签名"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true \
    --v3-signing-enabled true --v4-signing-enabled false \
    --out "$APK_FINAL" "$APK_ALIGNED"

say "校验签名"
"$APKSIGNER" verify --verbose "$APK_FINAL" | head -8

say "完成 → $APK_FINAL"
ls -la "$APK_FINAL"
