#!/bin/bash
# =============================================================================
# ZenViewer DMG Builder
# Builds a complete macOS DMG installer for ZenViewer (Weasis fork)
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Configuration
JDK="/Users/hasanerken/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home"
APP_NAME="ZenViewer"
APP_VERSION="1.0.0"
BUNDLE_ID="com.zenpacs.zenviewer"
ICON="$SCRIPT_DIR/weasis-distributions/script/resources/macosx/Weasis.icns"
INPUT_DIR="$SCRIPT_DIR/weasis-distributions/target/native-dist/bin-dist/bin-dist/weasis"
OUTPUT_DIR="$SCRIPT_DIR/target"
ENTITLEMENTS="/tmp/zenviewer-entitlements.plist"
DMG_OUTPUT="$OUTPUT_DIR/${APP_NAME}-${APP_VERSION}.dmg"

echo "============================================="
echo "  ZenViewer DMG Builder v${APP_VERSION}"
echo "============================================="

# --- Step 1: Full Maven Build ---
echo ""
echo "[1/8] Building Weasis project..."
# Clean stale targets first to avoid lock issues
rm -rf weasis-core/target 2>/dev/null || true

mvn clean install -DskipTests -q
echo "  ✓ Maven build complete"

# --- Step 2: Build native distribution ---
echo ""
echo "[2/8] Building native distribution..."
cd weasis-distributions
mvn clean install -DskipTests -q
cd target/native-dist
if [ -f weasis-native.zip ]; then
  rm -rf bin-dist 2>/dev/null || true
  unzip -qo weasis-native.zip -d bin-dist
  echo "  ✓ Native distribution extracted"
else
  echo "  ✗ ERROR: weasis-native.zip not found"
  exit 1
fi
cd "$SCRIPT_DIR"

# Verify input dir exists
if [ ! -d "$INPUT_DIR" ]; then
  echo "  ✗ ERROR: Input directory not found: $INPUT_DIR"
  exit 1
fi

# --- Step 3: Remove old app and run jpackage ---
echo ""
echo "[3/8] Running jpackage (app-image)..."
rm -rf "$OUTPUT_DIR/${APP_NAME}.app" 2>/dev/null || true
mkdir -p "$OUTPUT_DIR"

"$JDK/bin/jpackage" \
  --type app-image \
  --name "$APP_NAME" \
  --input "$INPUT_DIR" \
  --main-jar weasis-launcher.jar \
  --main-class org.weasis.launcher.AppLauncher \
  --java-options "-Xms64m" \
  --java-options "-Xmx768m" \
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
  --icon "$ICON" \
  --app-version "$APP_VERSION" \
  --mac-package-identifier "$BUNDLE_ID" \
  --dest "$OUTPUT_DIR"

APP_PATH="$OUTPUT_DIR/${APP_NAME}.app"
if [ ! -d "$APP_PATH" ]; then
  echo "  ✗ ERROR: jpackage failed to create app"
  exit 1
fi
echo "  ✓ App image created at $APP_PATH"

# --- Step 4: Fix .cfg (CRITICAL for OSGi) ---
echo ""
echo "[4/8] Fixing .cfg classpath (OSGi class space fix)..."
CFG_FILE="$APP_PATH/Contents/app/${APP_NAME}.cfg"

# Keep only the [Application] header, main class, launcher jar, and felix jar
# Remove ALL bundle/*.jar entries that jpackage auto-adds
python3 -c "
import re
with open('$CFG_FILE', 'r') as f:
    content = f.read()

lines = content.split('\n')
filtered = []
for line in lines:
    # Skip any classpath line that references bundle/
    if 'app.classpath=' in line and 'bundle/' in line:
        continue
    # Skip any classpath line that references bundle-i18n/
    if 'app.classpath=' in line and 'bundle-i18n/' in line:
        continue
    # Skip classpath entries for anything other than launcher and felix
    if 'app.classpath=' in line:
        if 'weasis-launcher.jar' not in line and 'felix.jar' not in line:
            continue
    filtered.append(line)

with open('$CFG_FILE', 'w') as f:
    f.write('\n'.join(filtered))
"
echo "  ✓ Classpath fixed (only weasis-launcher.jar + felix.jar)"

# --- Step 5: Add zenviewer:// URL scheme + audio entitlement to Info.plist ---
echo ""
echo "[5/8] Patching Info.plist..."
PLIST="$APP_PATH/Contents/Info.plist"

# Add CFBundleURLTypes for zenviewer:// URL scheme
/usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes array" "$PLIST" 2>/dev/null || true
/usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes:0 dict" "$PLIST" 2>/dev/null || true
/usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes:0:CFBundleURLName string '${APP_NAME} URL Handler'" "$PLIST" 2>/dev/null || true
/usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes:0:CFBundleURLSchemes array" "$PLIST" 2>/dev/null || true
/usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes:0:CFBundleURLSchemes:0 string 'zenviewer'" "$PLIST" 2>/dev/null || true

# Add microphone usage description
/usr/libexec/PlistBuddy -c "Add :NSMicrophoneUsageDescription string 'The application ${APP_NAME} is requesting access to the microphone.'" "$PLIST" 2>/dev/null || true

# Set app category
/usr/libexec/PlistBuddy -c "Add :LSApplicationCategoryType string 'public.app-category.medical'" "$PLIST" 2>/dev/null || \
/usr/libexec/PlistBuddy -c "Set :LSApplicationCategoryType 'public.app-category.medical'" "$PLIST" 2>/dev/null || true

echo "  ✓ Info.plist patched (zenviewer:// scheme + microphone permission)"

# --- Step 6: Write entitlements file ---
echo ""
echo "[6/8] Writing entitlements..."
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
echo "  ✓ Entitlements written (includes audio-input)"

# --- Step 7: Ad-hoc codesign with entitlements ---
echo ""
echo "[7/8] Code signing..."
codesign --force --sign - --entitlements "$ENTITLEMENTS" --deep "$APP_PATH"
echo "  ✓ App signed with entitlements"

# --- Step 8: Create DMG ---
echo ""
echo "[8/8] Creating DMG..."
rm -f "$DMG_OUTPUT" 2>/dev/null || true

# Create a temp directory for DMG contents
DMG_TEMP="$OUTPUT_DIR/dmg-temp"
rm -rf "$DMG_TEMP" 2>/dev/null || true
mkdir -p "$DMG_TEMP"

# Copy app and create Applications symlink
cp -R "$APP_PATH" "$DMG_TEMP/"
ln -s /Applications "$DMG_TEMP/Applications"

# Create DMG using hdiutil
hdiutil create -volname "$APP_NAME" \
  -srcfolder "$DMG_TEMP" \
  -ov -format UDZO \
  "$DMG_OUTPUT"

# Cleanup temp
rm -rf "$DMG_TEMP"

echo ""
echo "============================================="
echo "  BUILD COMPLETE"
echo "============================================="
echo ""
echo "  DMG: $DMG_OUTPUT"
echo "  Size: $(du -h "$DMG_OUTPUT" | cut -f1)"
echo ""
echo "  To install locally:"
echo "    1. Open $DMG_OUTPUT"
echo "    2. Drag ZenViewer to Applications"
echo "    3. Run: rm -rf ~/.weasis/cache-*"
echo "    4. Launch ZenViewer"
echo ""
echo "  To distribute:"
echo "    Copy $DMG_OUTPUT to target Macs"
echo "============================================="
