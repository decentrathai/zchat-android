#!/bin/bash
# Deploy APK to zsend.xyz download system
# Run this after building: ./deploy-apk.sh [version]
#
# The backend at api.zsend.xyz serves APKs from /home/yourt/
# It finds files matching *zchat*.apk and serves the newest by mtime

set -e

# Configuration
APK_SOURCE="app/build/outputs/apk/zcashmainnetFoss/debug/zchat-v2.8.1-zcashmainnetFossDebug.apk"
DOWNLOAD_DIR="/home/yourt"
WINDOWS_DIR="/mnt/c/Users/yourt/Downloads"

# Get version from argument or use date
VERSION="${1:-$(date +%Y%m%d)}"
APK_NAME="zchat-v${VERSION}.apk"

# Check if source APK exists
if [ ! -f "$APK_SOURCE" ]; then
    echo "ERROR: APK not found at $APK_SOURCE"
    echo "Run './gradlew assembleZcashmainnetFossDebug' first"
    exit 1
fi

# Remove old APKs from download directory (keep only latest)
echo "Removing old APKs..."
rm -f "$DOWNLOAD_DIR"/*zchat*.apk 2>/dev/null || true

# Copy new APK
echo "Deploying $APK_NAME..."
cp "$APK_SOURCE" "$DOWNLOAD_DIR/$APK_NAME"

# Update timestamp to ensure it's served as "newest"
touch "$DOWNLOAD_DIR/$APK_NAME"

# Also copy to Windows Downloads for easy access
if [ -d "$WINDOWS_DIR" ]; then
    cp "$APK_SOURCE" "$WINDOWS_DIR/$APK_NAME"
    echo "Also copied to Windows: $WINDOWS_DIR/$APK_NAME"
fi

# Show result
echo ""
echo "=== Deployment Complete ==="
ls -lah "$DOWNLOAD_DIR/$APK_NAME"
echo ""
echo "APK available for download at: https://zsend.xyz"
echo "Whitelisted users can download using their code."
