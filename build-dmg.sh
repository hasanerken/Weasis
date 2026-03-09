#!/bin/bash
# =============================================================================
# ZenViewer DMG Builder
# Builds macOS DMG installers for ZenViewer (Weasis fork)
# Supports: arm64 (Apple Silicon) and x86_64 (Intel)
#
# Usage:
#   ./build-dmg.sh              # Build for current architecture
#   ./build-dmg.sh arm64        # Build for Apple Silicon
#   ./build-dmg.sh x64          # Build for Intel
#   ./build-dmg.sh all          # Build both DMGs
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Configuration
JDK_ARM64="/Users/hasanerken/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home"
JDK_X64="/tmp/jdk22-x64/Contents/Home"
APP_NAME="ZenViewer"
# WARNING: When changing APP_VERSION, also update:
#   - build-windows.bat          (set APP_VERSION=...)
#   - weasis-launcher/conf/base.json  (zenviewer.version)
#   - weasis-update.json in MinIO bucket zenviewer-releases
APP_VERSION="1.0.13"
BUNDLE_ID="com.zenpacs.zenviewer"
ICON="$SCRIPT_DIR/weasis-distributions/script/resources/macosx/Weasis.icns"
INPUT_DIR="$SCRIPT_DIR/weasis-distributions/target/native-dist/bin-dist/bin-dist/weasis"
OUTPUT_DIR="$SCRIPT_DIR/target"
ENTITLEMENTS="/tmp/zenviewer-entitlements.plist"

# Determine target architecture(s)
TARGET="${1:-auto}"
if [ "$TARGET" = "auto" ]; then
  TARGET=$(uname -m)
  [ "$TARGET" = "arm64" ] || [ "$TARGET" = "aarch64" ] && TARGET="arm64"
  [ "$TARGET" = "x86_64" ] && TARGET="x64"
fi

build_app_image() {
  local ARCH=$1
  local JDK=$2
  local SUFFIX=$3

  echo ""
  echo "============================================="
  echo "  Building ZenViewer for ${ARCH}"
  echo "============================================="

  # Verify JDK
  if [ ! -x "$JDK/bin/jpackage" ]; then
    echo "  ERROR: JDK not found at $JDK"
    echo "  For x64, download Temurin JDK 22 x64 from https://adoptium.net/"
    echo "  Extract to /tmp/jdk22-x64/"
    return 1
  fi

  local APP_PATH="$OUTPUT_DIR/${APP_NAME}-${SUFFIX}.app"
  local DMG_OUTPUT="$OUTPUT_DIR/${APP_NAME}-${APP_VERSION}-${SUFFIX}.dmg"

  # --- jpackage ---
  echo "[1/5] Running jpackage (${ARCH})..."
  rm -rf "$APP_PATH" 2>/dev/null || true

  "$JDK/bin/jpackage" \
    --type app-image \
    --name "$APP_NAME" \
    --input "$INPUT_DIR" \
    --main-jar weasis-launcher.jar \
    --main-class org.weasis.launcher.AppLauncher \
    --java-options "-Xms512m" \
    --java-options "-Xmx6g" \
    --java-options "-XX:+UseG1GC" \
    --java-options "-XX:MaxGCPauseMillis=200" \
    --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" \
    --java-options "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED" \
    --java-options "--add-opens=java.base/java.text=ALL-UNNAMED" \
    --java-options "--add-opens=java.desktop/java.awt=ALL-UNNAMED" \
    --java-options "--add-opens=java.desktop/java.awt.image=ALL-UNNAMED" \
    --java-options "--add-opens=java.desktop/java.awt.color=ALL-UNNAMED" \
    --java-options "--add-opens=java.desktop/javax.swing=ALL-UNNAMED" \
    --java-options "--add-opens=java.desktop/javax.swing.border=ALL-UNNAMED" \
    --java-options "--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED" \
    --java-options "--add-opens=java.desktop/com.apple.laf=ALL-UNNAMED" \
    --java-options "--add-opens=java.desktop/sun.awt=ALL-UNNAMED" \
    --java-options "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED" \
    --java-options "-Dapple.laf.useScreenMenuBar=true" \
    --java-options "-Dapple.awt.application.appearance=system" \
    --java-options "-Djdk.xml.maxGeneralEntitySizeLimit=0" \
    --java-options "-Djdk.xml.totalEntitySizeLimit=0" \
    --icon "$ICON" \
    --app-version "$APP_VERSION" \
    --mac-package-identifier "$BUNDLE_ID" \
    --dest "$OUTPUT_DIR"

  # jpackage always outputs as APP_NAME.app, rename to include suffix
  mv "$OUTPUT_DIR/${APP_NAME}.app" "$APP_PATH"

  if [ ! -d "$APP_PATH" ]; then
    echo "  ERROR: jpackage failed"
    return 1
  fi
  echo "  OK"

  # --- Fix .cfg ---
  echo "[2/5] Fixing .cfg classpath..."
  local CFG_FILE="$APP_PATH/Contents/app/${APP_NAME}.cfg"
  python3 -c "
with open('$CFG_FILE', 'r') as f:
    lines = f.read().split('\n')
filtered = []
for line in lines:
    if 'app.classpath=' in line and 'bundle/' in line:
        continue
    if 'app.classpath=' in line and 'bundle-i18n/' in line:
        continue
    if 'app.classpath=' in line:
        if 'weasis-launcher.jar' not in line and 'felix.jar' not in line:
            continue
    filtered.append(line)
with open('$CFG_FILE', 'w') as f:
    f.write('\n'.join(filtered))
"
  echo "  OK"

  # --- Patch Info.plist ---
  echo "[3/5] Patching Info.plist..."
  local PLIST="$APP_PATH/Contents/Info.plist"
  /usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes array" "$PLIST" 2>/dev/null || true
  /usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes:0 dict" "$PLIST" 2>/dev/null || true
  /usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes:0:CFBundleURLName string '${APP_NAME} URL Handler'" "$PLIST" 2>/dev/null || true
  /usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes:0:CFBundleURLSchemes array" "$PLIST" 2>/dev/null || true
  /usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes:0:CFBundleURLSchemes:0 string 'zenviewer'" "$PLIST" 2>/dev/null || true
  /usr/libexec/PlistBuddy -c "Add :NSMicrophoneUsageDescription string 'The application ${APP_NAME} is requesting access to the microphone.'" "$PLIST" 2>/dev/null || true
  /usr/libexec/PlistBuddy -c "Add :LSApplicationCategoryType string 'public.app-category.medical'" "$PLIST" 2>/dev/null || \
  /usr/libexec/PlistBuddy -c "Set :LSApplicationCategoryType 'public.app-category.medical'" "$PLIST" 2>/dev/null || true
  echo "  OK"

  # --- Codesign ---
  echo "[4/5] Code signing..."
  codesign --force --sign - --entitlements "$ENTITLEMENTS" --deep "$APP_PATH"
  echo "  OK"

  # --- Create DMG ---
  echo "[5/5] Creating DMG..."
  rm -f "$DMG_OUTPUT" 2>/dev/null || true
  local DMG_TEMP="$OUTPUT_DIR/dmg-temp-${SUFFIX}"
  rm -rf "$DMG_TEMP" 2>/dev/null || true
  mkdir -p "$DMG_TEMP"

  # Copy app (rename to plain ZenViewer.app inside DMG)
  cp -R "$APP_PATH" "$DMG_TEMP/${APP_NAME}.app"
  ln -s /Applications "$DMG_TEMP/Applications"

  hdiutil create -volname "$APP_NAME" \
    -srcfolder "$DMG_TEMP" \
    -ov -format UDZO \
    "$DMG_OUTPUT"

  rm -rf "$DMG_TEMP"
  echo "  DMG: $DMG_OUTPUT ($(du -h "$DMG_OUTPUT" | cut -f1))"
}

echo "============================================="
echo "  ZenViewer DMG Builder v${APP_VERSION}"
echo "============================================="

# --- Step 1: Maven Build ---
echo ""
echo "[MAVEN] Building Weasis project..."
rm -rf weasis-core/target 2>/dev/null || true
mvn clean install -DskipTests -q
echo "  OK Maven build complete"

# --- Step 2: Native distribution ---
echo ""
echo "[DIST] Building native distribution..."
cd weasis-distributions
mvn clean install -DskipTests -q
cd target/native-dist
if [ -f weasis-native.zip ]; then
  rm -rf bin-dist 2>/dev/null || true
  unzip -qo weasis-native.zip -d bin-dist
  echo "  OK"
else
  echo "  ERROR: weasis-native.zip not found"
  exit 1
fi
cd "$SCRIPT_DIR"

if [ ! -d "$INPUT_DIR" ]; then
  echo "  ERROR: Input directory not found: $INPUT_DIR"
  exit 1
fi

# --- Write entitlements ---
cat > "$ENTITLEMENTS" << 'ENTITLEMENTS_EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
    <dict>
        <key>com.apple.security.app-sandbox</key>
        <false/>
        <key>com.apple.security.network.server</key>
        <true/>
        <key>com.apple.security.network.client</key>
        <true/>
        <key>com.apple.security.files.user-selected.read-write</key>
        <true/>
        <key>com.apple.security.cs.allow-jit</key>
        <true/>
        <key>com.apple.security.cs.allow-unsigned-executable-memory</key>
        <true/>
        <key>com.apple.security.cs.disable-executable-page-protection</key>
        <true/>
        <key>com.apple.security.cs.disable-library-validation</key>
        <true/>
        <key>com.apple.security.cs.allow-dyld-environment-variables</key>
        <true/>
        <key>com.apple.security.cs.debugger</key>
        <true/>
        <key>com.apple.security.device.audio-input</key>
        <true/>
    </dict>
</plist>
ENTITLEMENTS_EOF

mkdir -p "$OUTPUT_DIR"

# --- Build for target architecture(s) ---
case "$TARGET" in
  arm64)
    build_app_image "arm64" "$JDK_ARM64" "arm64"
    ;;
  x64|x86_64|intel)
    build_app_image "x86_64" "$JDK_X64" "x64"
    ;;
  all|both)
    build_app_image "arm64" "$JDK_ARM64" "arm64"
    build_app_image "x86_64" "$JDK_X64" "x64"
    ;;
  *)
    echo "Unknown target: $TARGET"
    echo "Usage: $0 [arm64|x64|all]"
    exit 1
    ;;
esac

echo ""
echo "============================================="
echo "  BUILD COMPLETE"
echo "============================================="
echo ""
ls -lh "$OUTPUT_DIR"/${APP_NAME}-${APP_VERSION}*.dmg 2>/dev/null | awk '{print "  " $NF " (" $5 ")"}'
echo ""
echo "  Install: open DMG, drag to Applications"
echo "  First launch: right-click -> Open (Gatekeeper bypass)"
echo "  Clear cache: rm -rf ~/.weasis/cache-*"
echo "============================================="
