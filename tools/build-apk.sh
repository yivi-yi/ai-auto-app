#!/bin/bash
# 本地编 APK（Actions 被账单挡住 / 想快点看效果时用）
#   bash tools/build-apk.sh          # 只编，产物放 apk-out/app-debug.apk
#   bash tools/build-apk.sh --push   # 编完推到 apk 分支（下载链接固定）
#
# 依赖：JDK17、gradle（/opt/gradle-8.9 或 PATH 里的）、Android SDK（ANDROID_HOME）
# 小坑：arm64 机器上 aapt2 要 box64 包一层，见下面的 aapt2FromMavenOverride。
set -o pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}
GRADLE=${GRADLE:-/opt/gradle-8.9/bin/gradle}
BT=$ANDROID_HOME/build-tools/35.0.0
[ -x "$GRADLE" ] || GRADLE=gradle

echo "== 编 =="
cd "$REPO" || exit 1
"$GRADLE" :app:assembleDebug --no-daemon --offline --console=plain \
  ${AAPT2_OVERRIDE:+-Pandroid.aapt2FromMavenOverride="$AAPT2_OVERRIDE"} \
  -Dorg.gradle.jvmargs=-Xmx3g 2>&1 | tail -20

APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "!! 没出包，看上面的 e: 报错"; exit 1; }
mkdir -p "$REPO/apk-out" && cp "$APK" "$REPO/apk-out/app-debug.apk"
echo "出包：$REPO/apk-out/app-debug.apk（$(stat -c%s "$APK") 字节）"

if [ "$1" = "--push" ]; then
  cd "$REPO" || exit 1
  SHA=$(git rev-parse --short HEAD)
  git checkout -B apk main
  git add -f apk-out/app-debug.apk
  git commit -m "本地编的 APK（对应 main $SHA）" || true
  git push -f origin apk
  git checkout main
  echo "已推 apk 分支；下载：<repo>/raw/apk/apk-out/app-debug.apk"
fi
