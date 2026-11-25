#!/usr/bin/env bash
# Builds dist/touchpad.apk with the bare SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"

# Defaults match tools/setup.ps1; override with ANDROID_SDK_ROOT / TOUCHPAD_JDK.
SDK=$(cygpath -m "${ANDROID_SDK_ROOT:-$USERPROFILE/Android/Sdk}")
JDK=$(cygpath -m "${TOUCHPAD_JDK:-$USERPROFILE/Android/jdk17}")
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="$JDK" PATH="$(cygpath -u "$JDK")/bin:$PATH"

rm -rf build && mkdir -p build/gen build/classes build/dex ../dist

"$BT/aapt2.exe" compile --dir res -o build/res.zip
VER=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
"$BT/aapt2.exe" link -o build/app.unsigned.apk -I "$AJ" --manifest AndroidManifest.xml \
  --java build/gen build/res.zip

find src build/gen -name '*.java' > build/sources.txt
javac -encoding UTF-8 --release 11 -classpath "$AJ" -d build/classes @build/sources.txt

find build/classes -name '*.class' > build/classes.txt
"$BT/d8.bat" --release --lib "$AJ" --min-api 26 --output build/dex $(cat build/classes.txt | tr '\n' ' ')

python - <<'EOF'
import zipfile
with zipfile.ZipFile("build/app.unsigned.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write("build/dex/classes.dex", "classes.dex")
EOF

"$BT/zipalign.exe" -p -f 4 build/app.unsigned.apk build/app.aligned.apk

KS=keystore.jks
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias touchpad -storepass touchpad -keypass touchpad \
    -dname "CN=TouchPad" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:touchpad --key-pass pass:touchpad \
  --out ../dist/touchpad.apk build/app.aligned.apk
"$BT/apksigner.bat" verify ../dist/touchpad.apk
echo "built ../dist/touchpad.apk (versionCode $VER)"
