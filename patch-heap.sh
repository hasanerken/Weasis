#!/bin/bash
# ZenViewer heap patcher — run this on any Mac with ZenViewer installed
# Usage: bash patch-heap.sh [heap_size]
# Example: bash patch-heap.sh 6g   (default: 6g)

HEAP="${1:-6g}"
CFG="/Applications/ZenViewer.app/Contents/app/ZenViewer.cfg"

if [ ! -f "$CFG" ]; then
  echo "ERROR: ZenViewer not found at /Applications/ZenViewer.app"
  exit 1
fi

CURRENT=$(grep -o '\-Xmx[^ ]*' "$CFG" || echo "not set")
sed -i '' "s/-Xmx[0-9]*[gGmM]/-Xmx${HEAP}/" "$CFG"
echo "Updated: $CURRENT → -Xmx${HEAP}"
echo "Restart ZenViewer for the change to take effect."
