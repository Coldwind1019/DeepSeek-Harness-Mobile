#!/bin/bash
# DeepSeek Harness Mobile 构建脚本
# 环境要求与依赖准备见 docs/BUILD.md
set -e
BT=/usr/lib/android-sdk/build-tools/29.0.3
AAPT2="$BT/aapt2"
D8_JAR=/root/toolchain/build-tools/android-14/lib/d8.jar
PLATFORM=/root/toolchain/platform/android-34/android.jar
APKSIGNER_JAR=/root/toolchain/build-tools/android-14/lib/apksigner.jar
SZ_API_JAR=/root/build/shizuku/unzip-api-12.2.0/classes.jar
SZ_PROVIDER_JAR=/root/build/shizuku/unzip-provider-12.2.0/classes.jar
DZ_JAR=/root/dhizuku-sdk/classes.jar
SZ_SHARED_JAR=/root/build/shizuku/unzip-shared-12.2.0/classes.jar
ZIPALIGN=/usr/bin/zipalign
APP=/root/build/app
OUT=/root/build/out
DIST=/storage/emulated/0/DEH/交付目录

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

echo "[1/8] aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/8] aapt2 link"
"$AAPT2" link -o "$OUT/base.apk" -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" --java "$OUT/gen" \
  -A "$APP/assets" --auto-add-overlay "$OUT/res.zip"

echo "[3/8] aidl"

echo "[3b/8] javac"
javac -encoding UTF-8 -source 8 -target 8 -classpath "$PLATFORM:$SZ_API_JAR:$SZ_PROVIDER_JAR:$SZ_SHARED_JAR:$DZ_JAR" -d "$OUT/classes" \
  "$APP"/src/com/deh/mobile/*.java "$OUT"/gen/com/deh/mobile/R.java

echo "[4/8] d8"
java -cp "$D8_JAR" com.android.tools.r8.D8 --lib "$PLATFORM" --min-api 26 \
  --output "$OUT/dex" "$OUT"/classes/com/deh/mobile/*.class \
  /root/build/shizuku/sample-classes.dex "$DZ_JAR"

echo "[5/8] merge dex + lib"
python3 - "$OUT" <<'PY'
import sys, os, zipfile
out = sys.argv[1]
base = os.path.join(out, 'base.apk')
final = os.path.join(out, 'final.apk')
with zipfile.ZipFile(base) as zin, zipfile.ZipFile(final, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    dexdir = os.path.join(out, 'dex')
    for name in sorted(os.listdir(dexdir)):
        if name.endswith('.dex'):
            zout.write(os.path.join(dexdir, name), name)
    libdir = '/root/build/app/lib'
    for dirpath, dirs, files in os.walk(libdir):
        for f in files:
            p = os.path.join(dirpath, f)
            rel = 'lib/' + os.path.relpath(p, libdir)
            zout.write(p, rel)
print('merged:', final)
PY

echo "[6/8] zipalign"
"$ZIPALIGN" -f -p 4 "$OUT/final.apk" "$OUT/aligned.apk"

echo "[7/8] sign"
KS=/root/old.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias androiddebugkey -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
rm -f "$OUT/signed.apk"
java -jar "$APKSIGNER_JAR" sign --ks "$KS" --ks-key-alias androiddebugkey --ks-pass pass:android \
  --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/signed.apk" "$OUT/aligned.apk"

echo "[8/8] verify + copy"
cp -f "$OUT/signed.apk" "$DIST/DeepSeek-Harness_3.0-stable_开源干净版_20261027.apk"
java -jar "$APKSIGNER_JAR" verify --verbose "$DIST/DeepSeek-Harness_3.0-stable_开源干净版_20261027.apk" | head -6
ls -lh "$DIST/DeepSeek-Harness_3.0-stable_开源干净版_20261027.apk"
echo "BUILD OK"
