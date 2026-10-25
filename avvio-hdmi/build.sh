#!/usr/bin/env bash
# Compila Avvio HDMI senza Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
set -euo pipefail
cd "$(dirname "$0")"
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

rm -rf out && mkdir -p out/gen out/obj out/dex
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml --java out/gen \
  --min-sdk-version 24 --target-sdk-version 34 --version-code "$VC" --version-name "$VN" out/res.zip
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$JAR" -classpath "$JAR" -d out/obj \
  $(find src out/gen -name '*.java')
"$BT/d8" --release --min-api 24 --lib "$JAR" --output out/dex $(find out/obj -name '*.class')
cp out/base.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 out/unsigned.apk out/aligned.apk
"$BT/apksigner" sign --ks avviohdmi.keystore --ks-pass pass:avviohdmi --key-pass pass:avviohdmi \
  --out out/AvvioHDMI.apk out/aligned.apk
"$BT/apksigner" verify out/AvvioHDMI.apk
echo "Pronto: out/AvvioHDMI.apk ($VN)"
