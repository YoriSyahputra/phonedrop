#!/usr/bin/env bash
# ==============================================================================
# PhoneDrop — Ultra-Lightweight Native Android APK Builder (Tanpa Gradle)
# Dirancang khusus untuk efisiensi RAM (< 80 MB) pada prosesor Intel Celeron.
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="/tmp/phonedrop-apk-build"

SDK_DIR="/usr/lib/android-sdk"
BUILD_TOOLS="$SDK_DIR/build-tools/35.0.0"
PLATFORM_JAR="$SDK_DIR/platforms/android-36/android.jar"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

KEYSTORE_DIR="$HOME/.config/phonedrop"
KEYSTORE="$KEYSTORE_DIR/android-debug.keystore"
OUT_APK="$PROJECT_DIR/web/phonedrop.apk"

# Deteksi IP LAN aktif agar langsung terisi di APK
LAN_IP=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}')
if [ -z "$LAN_IP" ]; then
    LAN_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
fi
if [ -z "$LAN_IP" ]; then
    LAN_IP="192.168.1.8"
fi
DEFAULT_SERVER="http://${LAN_IP}:8080"

echo "[APK] Menyiapkan resource (Server default: $DEFAULT_SERVER)..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/compiled_res" "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex"
mkdir -p "$SCRIPT_DIR/res/drawable" "$SCRIPT_DIR/res/values"

cp "$PROJECT_DIR/web/icon-192.png" "$SCRIPT_DIR/res/drawable/ic_launcher.png"

cat > "$SCRIPT_DIR/res/values/strings.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">PhoneDrop</string>
    <string name="default_server">${DEFAULT_SERVER}</string>
</resources>
EOF

echo "[APK] 1/5 Mengompilasi resource dengan aapt2..."
"$AAPT2" compile --dir "$SCRIPT_DIR/res" -o "$BUILD_DIR/compiled_res/res.zip"
"$AAPT2" link \
    -I "$PLATFORM_JAR" \
    --manifest "$SCRIPT_DIR/AndroidManifest.xml" \
    --java "$BUILD_DIR/gen" \
    -o "$BUILD_DIR/unaligned.apk" \
    "$BUILD_DIR/compiled_res/res.zip"

echo "[APK] 2/5 Mengompilasi kode Java (javac)..."
javac --release 11 \
    -cp "$PLATFORM_JAR" \
    -d "$BUILD_DIR/classes" \
    "$BUILD_DIR/gen/id/phonedrop/app/R.java" \
    "$SCRIPT_DIR/src/id/phonedrop/app/DirectSendService.java" \
    "$SCRIPT_DIR/src/id/phonedrop/app/MainActivity.java"

echo "[APK] 3/5 Mengonversi bytecode ke DEX (d8)..."
CLASS_FILES=$(find "$BUILD_DIR/classes" -name "*.class")
"$D8" --lib "$PLATFORM_JAR" --min-api 26 --output "$BUILD_DIR/dex" $CLASS_FILES

(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/unaligned.apk" classes.dex)

echo "[APK] 4/5 Merapikan alignment APK (zipalign)..."
"$ZIPALIGN" -f -p 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

mkdir -p "$KEYSTORE_DIR"
if [ ! -f "$KEYSTORE" ]; then
    echo "[APK] Membuat keystore penandatanganan..."
    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -alias phonedrop \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass phonedrop123 \
        -keypass phonedrop123 \
        -dname "CN=PhoneDrop, O=PhoneDrop, C=ID" >/dev/null 2>&1
fi

echo "[APK] 5/5 Menandatangani APK (apksigner)..."
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias phonedrop \
    --ks-pass pass:phonedrop123 \
    --key-pass pass:phonedrop123 \
    --out "$OUT_APK" \
    "$BUILD_DIR/aligned.apk"

rm -rf "$BUILD_DIR"
ls -lh "$OUT_APK"
echo "[APK] Selesai! APK siap di: $OUT_APK"
