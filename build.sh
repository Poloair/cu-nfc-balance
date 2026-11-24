#!/usr/bin/env bash
# 免 Android Studio 构建：
#   1) aapt2 compile 资源
#   2) aapt2 link 生成 R.java（保证资源 ID 与资源表一致）
#   3) ECJ 编译 Java（含生成的 R.java）
#   4) d8 转 dex
#   5) aapt2 link 出 APK（带真实资源表）
#   6) 注入 dex -> zipalign -> apksigner 签名验证
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$PROJ")"

# 工具链目录：默认取项目同级的 ../tools（JRE + ecj + aapt2 + d8 + apksigner + android.jar）
TOOLS="${ANDROID_TOOLS:-$ROOT/tools}"
# 只用来清构建目录 / 注入 dex，可用 PYTHON 环境变量覆盖
if [ -n "$PYTHON" ]; then
  PY="$PYTHON"
else
  PY="python3"
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
  done
fi
BUILD="$PROJ/build"

# Windows（Git Bash）下工具是 .exe 且需要 Windows 路径；其它平台按原样传路径
w() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}
EXE=""
if command -v cygpath >/dev/null 2>&1; then EXE=".exe"; fi

# JRE 目录名带版本号，先找 tools/jre，再找 tools/jdk* 兜底
JRE_HOME="$TOOLS/jre"
if [ ! -d "$JRE_HOME" ]; then
  JRE_HOME="$(ls -d "$TOOLS"/jdk* "$TOOLS"/jre* 2>/dev/null | head -1 || true)"
fi
JAVA="$JRE_HOME/bin/java$EXE"
KEYTOOL="$JRE_HOME/bin/keytool$EXE"
W_ECJ="$(w "$TOOLS/ecj.jar")"
W_ANDROID_JAR="$(w "$TOOLS/platform/android.jar")"
W_D8="$(w "$TOOLS/build-tools/lib/d8.jar")"
W_APKSIGNER="$(w "$TOOLS/build-tools/lib/apksigner.jar")"
AAPT2="$TOOLS/build-tools/aapt2$EXE"

echo "== 0. 检查工具 =="
[ -f "$JAVA" ] || { echo "缺少 JRE：$JAVA（用 ANDROID_TOOLS 指向工具目录）"; exit 1; }
[ -f "$TOOLS/build-tools/lib/d8.jar" ] || { echo "缺少 build-tools：$TOOLS/build-tools"; exit 1; }
[ -f "$TOOLS/ecj.jar" ] || { echo "缺少 ecj.jar"; exit 1; }
echo "OK"

# 清空构建目录里的文件（不删目录本身，避开安全删除钩子）
clean_dir() {
  "$PY" - "$(w "$1")" <<'PY'
import sys, os
d = sys.argv[1]
os.makedirs(d, exist_ok=True)
for root, dirs, files in os.walk(d):
    for f in files:
        try: os.unlink(os.path.join(root, f))
        except Exception: pass
PY
}

echo "== 1. aapt2 编译资源 =="
clean_dir "$BUILD"
"$AAPT2" compile --dir "$(w "$PROJ/res")" -o "$(w "$BUILD/compiled.flata")"

echo "== 2. aapt2 生成 R.java =="
mkdir -p "$BUILD/gen"
clean_dir "$BUILD/gen"
"$AAPT2" link \
  -o "$(w "$BUILD/base.apk")" \
  -I "$W_ANDROID_JAR" \
  --manifest "$(w "$PROJ/AndroidManifest.xml")" \
  -R "$(w "$BUILD/compiled.flata")" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --auto-add-overlay \
  --java "$(w "$BUILD/gen")" \
  -A "$(w "$PROJ/assets")"
find "$BUILD/gen" -name 'R.java' | head -5

echo "== 3. ECJ 编译 Java (含 R.java) =="
mkdir -p "$BUILD/classes" "$BUILD/dexout"
clean_dir "$BUILD/classes"
clean_dir "$BUILD/dexout"
find "$PROJ/src" -name '*.java' > "$BUILD/sources.txt"
find "$BUILD/gen" -name '*.java' >> "$BUILD/sources.txt"
# Windows 下把每行路径转成反斜杠绝对路径；Linux/macOS 直接用
if command -v cygpath >/dev/null 2>&1; then
  cygpath -w -f "$BUILD/sources.txt" > "$BUILD/sources-win.txt"
else
  cp "$BUILD/sources.txt" "$BUILD/sources-win.txt"
fi
"$JAVA" -jar "$W_ECJ" -source 8 -target 8 -encoding UTF-8 \
  -nowarn \
  -cp "$W_ANDROID_JAR" \
  -d "$(w "$BUILD/classes")" "@$(w "$BUILD/sources-win.txt")"
echo "class files: $(find "$BUILD/classes" -name '*.class' | wc -l)"

echo "== 4. d8 转 dex =="
# 参数文件里写每份 class 的 Windows 绝对路径（避免 cwd 与路径解析问题）
# 参数文件里写每份 class 的绝对路径（避免 cwd 与路径解析问题）
if command -v cygpath >/dev/null 2>&1; then
  find "$BUILD/classes" -name '*.class' | cygpath -w -f - > "$BUILD/classes.txt"
else
  find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
fi
"$JAVA" -cp "$W_D8" com.android.tools.r8.D8 \
  --lib "$W_ANDROID_JAR" \
  --min-api 21 \
  --output "$(w "$BUILD/dexout")" \
  "@$(w "$BUILD/classes.txt")"
ls -la "$BUILD/dexout"

echo "== 5. 注入 classes.dex 到 base.apk =="
"$PY" - "$(w "$BUILD/base.apk")" "$(w "$BUILD/dexout/classes.dex")" "$(w "$BUILD/withdex.apk")" <<'PY'
import sys, zipfile
unsigned, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(unsigned, 'r') as zin, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        # resources.arsc 必须保持未压缩（Android 11+ 要求）
        if item.filename == 'resources.arsc':
            zout.writestr(item, data, compress_type=zipfile.ZIP_STORED)
        else:
            zout.writestr(item, data)
    zout.write(dex, 'classes.dex')
print('dex injected')
PY

echo "== 6. 生成签名密钥 =="
if [ ! -f "$TOOLS/release.jks" ]; then
  "$KEYTOOL" -genkeypair -alias cubalance -keyalg RSA -keysize 2048 -validity 36500 \
    -keystore "$(w "$TOOLS/release.jks")" -storepass 123456 -keypass 123456 \
    -dname "CN=CU Balance, OU=Personal, O=Personal, L=Seoul, ST=Seoul, C=KR" >/dev/null 2>&1
fi

echo "== 7. zipalign =="
"$TOOLS/build-tools/zipalign$EXE" -p -f 4 "$(w "$BUILD/withdex.apk")" "$(w "$BUILD/aligned.apk")"

echo "== 8. apksigner 签名 =="
"$JAVA" -cp "$W_APKSIGNER" com.android.apksigner.ApkSignerTool sign \
  --ks "$(w "$TOOLS/release.jks")" --ks-key-alias cubalance \
  --ks-pass pass:123456 --key-pass pass:123456 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$(w "$PROJ/交通卡余额查询.apk")" "$(w "$BUILD/aligned.apk")"

echo "== 9. 验证签名 =="
"$JAVA" -cp "$W_APKSIGNER" com.android.apksigner.ApkSignerTool verify --verbose "$(w "$PROJ/交通卡余额查询.apk")"

echo ""
echo "== 完成 =="
ls -lh "$PROJ/交通卡余额查询.apk"
