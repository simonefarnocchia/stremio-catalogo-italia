#!/usr/bin/env bash
# Compila un'app Android senza Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
# Uso: android/build.sh <cartella app> <nome file apk>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/$1"
NAME="$2"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BTV=34.0.0
PLAT=android-34
if [ ! -d "$SDK/build-tools/$BTV" ] || [ ! -f "$SDK/platforms/$PLAT/android.jar" ]; then
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" "build-tools;$BTV" "platforms;$PLAT" >/dev/null
fi
BT="$SDK/build-tools/$BTV"
JAR="$SDK/platforms/$PLAT/android.jar"
VC="${VERSION_CODE:-1}"
VN="1.0.$VC"
OUT="$APP/out"

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$APP/AndroidManifest.xml" --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 --version-code "$VC" --version-name "$VN" "$OUT/res.zip"
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$JAR" -classpath "$JAR" -d "$OUT/obj" \
  $(find "$APP/src" "$OUT/gen" -name '*.java')
"$BT/d8" --release --min-api 24 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/obj" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$ROOT/taverna.keystore" --ks-pass pass:avviohdmi --key-pass pass:avviohdmi \
  --out "$OUT/$NAME" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/$NAME"
echo "Pronto: $OUT/$NAME ($VN)"
