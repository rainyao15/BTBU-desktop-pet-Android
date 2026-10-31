#!/usr/bin/env bash
# 纯 Kotlin 类型检查（不需要 Android Studio / Gradle / aapt）
#
# 需要三样东西：
#   JAVA_HOME      JDK 17
#   KOTLINC        指向 kotlinc / kotlinc.bat
#   ANDROID_JAR    android.jar（platform-34）
#
# 例（Windows Git Bash）：
#   export JAVA_HOME='D:\jdk-17'
#   export KOTLINC=/d/kotlin/kotlinc/bin/kotlinc.bat
#   export ANDROID_JAR='D:\android-sdk\platforms\android-34\android.jar'
#   ./tools/typecheck.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

: "${JAVA_HOME:?请先 export JAVA_HOME}"
: "${KOTLINC:?请先 export KOTLINC 指向 kotlinc}"
: "${ANDROID_JAR:?请先 export ANDROID_JAR 指向 android.jar}"

python tools/gen_r_stub.py

SRC="$(find app/src/main/java -name '*.kt' | tr '\n' ' ')"
echo "==> kotlinc 编译 $(echo "$SRC" | wc -w) 个文件"
"$KOTLINC" -cp "$ANDROID_JAR" -d build/typecheck/out $SRC build/typecheck/R.kt 2>&1 | tee build/typecheck/kc.log

if grep -q "error:" build/typecheck/kc.log; then
    echo "==> 有编译错误："
    grep "error:" build/typecheck/kc.log
    exit 1
fi
echo "==> 类型检查通过（0 error）"
