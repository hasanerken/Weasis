#!/bin/bash
# =============================================================================
# ZenViewer Release Script
# Builds all platforms and uploads to MinIO
#
# Usage:
#   ./release.sh              # Full release: DMGs + Windows MSI + upload
#   ./release.sh dmg          # Build and upload DMGs only
#   ./release.sh windows      # Trigger Windows build, wait, download, upload
#   ./release.sh upload       # Upload existing artifacts from target/ to MinIO
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

REPO="hasanerken/ZenViewer"
MINIO_ALIAS="swfs"
MINIO_BUCKET="zenviewer-releases"

# Read version from build-dmg.sh
APP_VERSION=$(grep -m1 '^APP_VERSION=' build-dmg.sh | sed 's/APP_VERSION="\(.*\)"/\1/')
if [ -z "$APP_VERSION" ]; then
  echo "ERROR: Cannot read APP_VERSION from build-dmg.sh"
  exit 1
fi

echo "============================================="
echo "  ZenViewer Release v${APP_VERSION}"
echo "============================================="
echo ""

# --- Helper: Upload to MinIO ---
upload_to_minio() {
  local file="$1"
  local platform="$1
  "
  local filename=$(basename "$file")

  echo "  Uploading ${filename} to ${MINIO_BUCKET}/${APP_VERSION}/${platform}/..."
  mc cp "$file" "${MINIO_ALIAS}/${MINIO_BUCKET}/${APP_VERSION}/${platform}/"
  echo "  OK"
}

# --- Build DMGs ---
# WARNING: Never use ./build-dmg.sh all — each arch run does mvn clean which
# wipes target/, destroying the previously built DMG. Build and upload each arch separately.
build_dmgs() {
  echo "[DMG] Building arm64 DMG..."
  echo ""
  ./build-dmg.sh arm64
  echo ""
  local arm64_dmg="target/ZenViewer-${APP_VERSION}-arm64.dmg"
  if [ -f "$arm64_dmg" ]; then
    upload_to_minio "$arm64_dmg" "macos-arm64"
  else
    echo "  ERROR: $arm64_dmg not found after build"
    exit 1
  fi

  echo ""
  echo "[DMG] Building x64 DMG..."
  echo "  NOTE: Uses Temurin JDK 22 x64 (/tmp/jdk22-x64) for macOS 11.0+ compatibility"
  echo "        If /tmp/jdk22-x64 is missing, download it first:"
  echo "        curl -L 'https://github.com/adoptium/temurin22-binaries/releases/download/jdk-22.0.2%2B9/OpenJDK22U-jdk_x64_mac_hotspot_22.0.2_9.tar.gz' -o /tmp/temurin22-x64.tar.gz && cd /tmp && tar -xzf temurin22-x64.tar.gz && cp -R /tmp/jdk-22.0.2+9/Contents /tmp/jdk22-x64/"
  echo ""
  # Verify Temurin x64 JDK is available
  if [ ! -x "/tmp/jdk22-x64/Contents/Home/bin/jpackage" ]; then
    echo "  ERROR: Temurin JDK 22 x64 not found at /tmp/jdk22-x64"
    echo "  Download it with the command above, then re-run."
    exit 1
  fi
  ./build-dmg.sh x64
  echo ""
  local x64_dmg="target/ZenViewer-${APP_VERSION}-x64.dmg"
  if [ -f "$x64_dmg" ]; then
    upload_to_minio "$x64_dmg" "macos-x64"
  else
    echo "  ERROR: $x64_dmg not found after build"
    exit 1
  fi

  echo ""
  echo "[DMG] Build complete."
}

# --- Upload DMGs (upload-only, no build) ---
upload_dmgs() {
  local arm64_dmg="target/ZenViewer-${APP_VERSION}-arm64.dmg"
  local x64_dmg="target/ZenViewer-${APP_VERSION}-x64.dmg"

  echo "[UPLOAD] Uploading DMGs to MinIO..."

  if [ -f "$arm64_dmg" ]; then
    upload_to_minio "$arm64_dmg" "macos-arm64"
  else
    echo "  SKIP: $arm64_dmg not found"
  fi

  if [ -f "$x64_dmg" ]; then
    upload_to_minio "$x64_dmg" "macos-x64"
  else
    echo "  SKIP: $x64_dmg not found"
  fi
}

# --- Trigger Windows build, wait, download ---
build_windows() {
  echo "[WINDOWS] Triggering GitHub Actions build..."

  # Trigger the workflow
  gh workflow run "Build ZenViewer Windows MSI" --repo "$REPO" --ref master
  echo "  Triggered. Waiting 10s for run to register..."
  sleep 10

  # Find the run ID
  local run_id
  run_id=$(gh run list --repo "$REPO" --workflow "Build ZenViewer Windows MSI" --limit 1 --json databaseId --jq '.[0].databaseId')

  if [ -z "$run_id" ]; then
    echo "  ERROR: Could not find triggered run"
    exit 1
  fi

  echo "  Run ID: ${run_id}"
  echo "  Waiting for build to complete (checking every 30s)..."

  # Poll until complete
  local status="in_progress"
  local elapsed=0
  while [ "$status" != "completed" ]; do
    sleep 30
    elapsed=$((elapsed + 30))
    status=$(gh run view "$run_id" --repo "$REPO" --json status --jq '.status')
    local mins=$((elapsed / 60))
    local secs=$((elapsed % 60))
    echo "  [${mins}m${secs}s] Status: ${status}"
  done

  # Check conclusion
  local conclusion
  conclusion=$(gh run view "$run_id" --repo "$REPO" --json conclusion --jq '.conclusion')
  if [ "$conclusion" != "success" ]; then
    echo "  ERROR: Build failed with conclusion: ${conclusion}"
    echo "  See: https://github.com/${REPO}/actions/runs/${run_id}"
    exit 1
  fi

  echo "  Build succeeded!"
  echo ""

  # Download the MSI artifact
  echo "[WINDOWS] Downloading MSI artifact..."
  local dl_dir="target/gh-download"
  rm -rf "$dl_dir"
  mkdir -p "$dl_dir"
  gh run download "$run_id" --repo "$REPO" -D "$dl_dir"

  # Find the MSI file (inside artifact folder)
  local msi_file
  msi_file=$(find "$dl_dir" -name "*.msi" | head -1)

  if [ -z "$msi_file" ]; then
    echo "  ERROR: No MSI file found in downloaded artifacts"
    exit 1
  fi

  # Move MSI to target/
  cp "$msi_file" "target/ZenViewer-${APP_VERSION}-x86-64.msi"
  rm -rf "$dl_dir"
  echo "  OK: target/ZenViewer-${APP_VERSION}-x86-64.msi ($(du -h "target/ZenViewer-${APP_VERSION}-x86-64.msi" | cut -f1))"
}

# --- Upload Windows MSI ---
upload_windows() {
  local msi_file="target/ZenViewer-${APP_VERSION}-x86-64.msi"

  echo "[UPLOAD] Uploading Windows MSI to MinIO..."

  if [ -f "$msi_file" ]; then
    upload_to_minio "$msi_file" "windows-x64"
  else
    echo "  SKIP: $msi_file not found"
  fi
}

# --- Update weasis-update.json in MinIO ---
update_release_json() {
  echo "[UPDATE] Updating weasis-update.json in MinIO..."
  local today=$(date +%Y-%m-%d)
  local json_file="/tmp/weasis-update.json"

  cat > "$json_file" << EOF
{
  "version": "${APP_VERSION}",
  "released_at": "${today}T12:00:00Z",
  "platforms": {
    "macos-arm64": {
      "url": "${APP_VERSION}/macos-arm64/ZenViewer-${APP_VERSION}-arm64.dmg"
    },
    "macos-x64": {
      "url": "${APP_VERSION}/macos-x64/ZenViewer-${APP_VERSION}-x64.dmg"
    },
    "windows-x64": {
      "url": "${APP_VERSION}/windows-x64/ZenViewer-${APP_VERSION}-x86-64.msi"
    }
  }
}
EOF

  mc cp "$json_file" "${MINIO_ALIAS}/${MINIO_BUCKET}/weasis-update.json"
  rm -f "$json_file"
  echo "  OK"
}

# --- Print summary ---
print_summary() {
  echo ""
  echo "============================================="
  echo "  RELEASE COMPLETE - v${APP_VERSION}"
  echo "============================================="
  echo ""
  echo "  MinIO contents:"
  mc ls "${MINIO_ALIAS}/${MINIO_BUCKET}/${APP_VERSION}/" 2>/dev/null || true
  echo ""
  echo "  Release JSON:"
  mc cat "${MINIO_ALIAS}/${MINIO_BUCKET}/weasis-update.json" 2>/dev/null || true
  echo ""
  echo "============================================="
}

# --- Main ---
MODE="${1:-all}"

case "$MODE" in
  all)
    build_dmgs
    echo ""
    build_windows
    echo ""
    upload_windows
    echo ""
    update_release_json
    print_summary
    ;;
  dmg)
    build_dmgs
    ;;
  windows)
    build_windows
    echo ""
    upload_windows
    ;;
  upload)
    upload_dmgs
    echo ""
    upload_windows
    echo ""
    update_release_json
    print_summary
    ;;
  *)
    echo "Usage: $0 [all|dmg|windows|upload]"
    exit 1
    ;;
esac
