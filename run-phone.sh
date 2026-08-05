#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

APK_PATH="app/build/outputs/apk/debug/whisperflow-debug.apk"
PACKAGE="com.pimax.whisperflow"

echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${CYAN}  🎙️  WhisperFlow — Build & Deploy${NC}"
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

# Find device
DEVICES=$(adb devices 2>/dev/null | grep -v "List" | grep -v "^$" | awk '{print $1}')
if [ -z "$DEVICES" ]; then
    echo -e "${RED}❌ No devices found. Connect a phone or start an emulator.${NC}"
    echo ""
    echo "  Start emulator:"
    echo "    ~/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_36.1 &"
    exit 1
fi

DEVICE=$(echo "$DEVICES" | head -1)
DEVICE_COUNT=$(echo "$DEVICES" | wc -l | tr -d ' ')

if [ "$DEVICE_COUNT" -gt 1 ]; then
    echo -e "${YELLOW}⚠️  Multiple devices found:${NC}"
    echo "$DEVICES" | while read -r d; do echo "    $d"; done
    echo ""
    echo -e "Using first: ${GREEN}$DEVICE${NC}"
fi

echo -e "${YELLOW}📱 Device: ${GREEN}$DEVICE${NC}"
echo ""

# Build
echo -e "${YELLOW}🔨 Building...${NC}"
./gradlew assembleDebug 2>&1 | tail -3

if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}❌ Build failed — APK not found${NC}"
    exit 1
fi

SIZE=$(ls -lh "$APK_PATH" | awk '{print $5}')
echo -e "${GREEN}✅ Build complete — ${SIZE}${NC}"
echo ""

# Install
echo -e "${YELLOW}📦 Installing...${NC}"
adb -s "$DEVICE" install -r "$APK_PATH" 2>&1 | tail -1
echo ""

# Launch
echo -e "${YELLOW}🚀 Launching...${NC}"
adb -s "$DEVICE" shell am start -n "$PACKAGE/.MainActivity" 2>&1 | tail -1
echo ""

echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}✅ WhisperFlow deployed to $DEVICE${NC}"
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo -e "  ${YELLOW}Logs:${NC} adb -s $DEVICE logcat | grep -E 'WhisperApi|AudioRecorder|WhisperAssist|WhisperPTT'"
echo ""
