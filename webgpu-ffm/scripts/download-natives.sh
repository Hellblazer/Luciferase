#!/bin/bash

# WebGPU Native Library Download Script
# Downloads wgpu-native libraries for all supported platforms

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
NATIVES_DIR="$MODULE_DIR/src/main/resources/natives"

# Version to download
WGPU_VERSION="v0.19.4.1"

echo "WebGPU Native Library Downloader"
echo "================================"
echo "Version: $WGPU_VERSION"
echo "Target directory: $NATIVES_DIR"
echo ""

# Create directories
mkdir -p "$NATIVES_DIR/macos-aarch64"
mkdir -p "$NATIVES_DIR/macos-x86_64"
mkdir -p "$NATIVES_DIR/linux-x86_64"
mkdir -p "$NATIVES_DIR/windows-x86_64"

# Function to download and extract
download_platform() {
    local PLATFORM=$1
    local ARCH=$2
    local URL=$3
    local TARGET_DIR="$NATIVES_DIR/$PLATFORM-$ARCH"
    local TEMP_FILE="/tmp/wgpu-$PLATFORM-$ARCH.zip"
    
    echo "Downloading $PLATFORM-$ARCH..."
    curl -L "$URL" -o "$TEMP_FILE"
    
    if [ $? -eq 0 ]; then
        echo "Extracting to $TARGET_DIR..."
        unzip -o "$TEMP_FILE" -d "$TARGET_DIR"
        
        # Move the library to the correct name
        case "$PLATFORM" in
            macos)
                mv "$TARGET_DIR/libwgpu_native.dylib" "$TARGET_DIR/libwgpu_native.dylib" 2>/dev/null || true
                ;;
            linux)
                mv "$TARGET_DIR/libwgpu_native.so" "$TARGET_DIR/libwgpu_native.so" 2>/dev/null || true
                ;;
            windows)
                mv "$TARGET_DIR/wgpu_native.dll" "$TARGET_DIR/wgpu_native.dll" 2>/dev/null || true
                ;;
        esac
        
        echo "✓ $PLATFORM-$ARCH downloaded successfully"
        rm "$TEMP_FILE"
    else
        echo "✗ Failed to download $PLATFORM-$ARCH"
    fi
}

# Download all platforms
download_platform "macos" "aarch64" \
    "https://github.com/gfx-rs/wgpu-native/releases/download/$WGPU_VERSION/wgpu-macos-aarch64-release.zip"

download_platform "macos" "x86_64" \
    "https://github.com/gfx-rs/wgpu-native/releases/download/$WGPU_VERSION/wgpu-macos-x86_64-release.zip"

download_platform "linux" "x86_64" \
    "https://github.com/gfx-rs/wgpu-native/releases/download/$WGPU_VERSION/wgpu-linux-x86_64-release.zip"

download_platform "windows" "x86_64" \
    "https://github.com/gfx-rs/wgpu-native/releases/download/$WGPU_VERSION/wgpu-windows-x86_64-release.zip"

echo ""
echo "Download complete!"
echo "Libraries stored in: $NATIVES_DIR"

# Create version file
VERSION_FILE="$MODULE_DIR/src/main/resources/META-INF/webgpu-version.properties"
mkdir -p "$(dirname "$VERSION_FILE")"
cat > "$VERSION_FILE" <<EOF
# WebGPU Native Library Version Information
wgpu.version=$WGPU_VERSION
wgpu.release.date=$(date -u +"%Y-%m-%d")
wgpu.download.date=$(date -u +"%Y-%m-%d %H:%M:%S UTC")
EOF

echo "Version file created: $VERSION_FILE"