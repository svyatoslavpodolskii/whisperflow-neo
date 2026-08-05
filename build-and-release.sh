#!/bin/bash
# ──────────────────────────────────────────────────────────────
# WhisperFlow Android — Build & GitHub Release
# Usage:
#   ./build-and-release.sh              # auto-increment patch
#   ./build-and-release.sh 3.0          # explicit version
#   ./build-and-release.sh 3.0 --draft  # draft release
# ──────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"
APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/debug/whisperflow-debug.apk"
TAG_PREFIX="whisperflow"

RED='\033[0;31m'
GREEN='\033[0;32m'
AMBER='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${GREEN}✅ $1${NC}"; }
warn() { echo -e "${AMBER}⚠️  $1${NC}"; }
err()  { echo -e "${RED}❌ $1${NC}"; exit 1; }
info() { echo -e "${CYAN}🔍 $1${NC}"; }

# ── Pre-flight checks ───────────────────────────────────────

command -v gh >/dev/null 2>&1    || err "gh CLI not found. Install: brew install gh"
command -v ./gradlew >/dev/null 2>&1 || { cd "$SCRIPT_DIR"; }
[ -f "$SCRIPT_DIR/gradlew" ]     || err "gradlew not found in $SCRIPT_DIR"

cd "$SCRIPT_DIR"

# ── Determine version ───────────────────────────────────────

DRAFT_FLAG=""
if [ "${2:-}" = "--draft" ]; then
    DRAFT_FLAG="--draft"
fi

if [ -n "${1:-}" ] && [ "${1:-}" != "--draft" ]; then
    VERSION="$1"
else
    # Auto-detect latest whisperflow release and bump patch
    LATEST=$(gh release list --repo "$(cd "$SCRIPT_DIR" && gh repo view --json nameWithOwner -q .nameWithOwner)" \
        --limit 50 2>/dev/null \
        | grep "$TAG_PREFIX" \
        | head -1 \
        | awk '{print $1}' \
        | sed "s/${TAG_PREFIX}-v//" || echo "")

    if [ -z "$LATEST" ]; then
        VERSION="3.0"
        warn "No previous release found — defaulting to v$VERSION"
    else
        MAJOR=$(echo "$LATEST" | cut -d. -f1)
        MINOR=$(echo "$LATEST" | cut -d. -f2)
        VERSION="${MAJOR}.$((MINOR + 1))"
        info "Latest release: v$LATEST → bumping to v$VERSION"
    fi
fi

TAG="${TAG_PREFIX}-v${VERSION}"
APK_NAME="WhisperFlow-v${VERSION}.apk"

echo ""
echo "══════════════════════════════════════════════════"
echo "  🎙️ WhisperFlow Android — Build & Release"
echo "  Version: v${VERSION}    Tag: ${TAG}"
echo "══════════════════════════════════════════════════"
echo ""

# ── Check for uncommitted changes ────────────────────────────

cd "$REPO_ROOT"
if ! git diff --quiet HEAD; then
    warn "You have uncommitted changes"
    echo ""
    git diff --stat HEAD
    echo ""
    read -p "Continue anyway? (y/N) " -n 1 -r
    echo ""
    [[ $REPLY =~ ^[Yy]$ ]] || err "Aborted. Commit your changes first."
fi

# ── Build ────────────────────────────────────────────────────

cd "$SCRIPT_DIR"

info "Cleaning previous build..."
./gradlew clean 2>&1 | tail -1

info "Building debug APK..."
BUILD_START=$(date +%s)
./gradlew assembleDebug 2>&1 | tail -3
BUILD_END=$(date +%s)
BUILD_TIME=$((BUILD_END - BUILD_START))

[ -f "$APK_PATH" ] || err "APK not found at $APK_PATH — build failed?"

APK_SIZE=$(ls -lh "$APK_PATH" | awk '{print $5}')
log "Build complete in ${BUILD_TIME}s — APK: $APK_SIZE"

# ── Size sanity check ────────────────────────────────────────

APK_BYTES=$(stat -f%z "$APK_PATH" 2>/dev/null || stat -c%s "$APK_PATH" 2>/dev/null)
MAX_BYTES=$((35 * 1024 * 1024))  # 35MB (WhisperFlow includes TFLite deps)

if [ "$APK_BYTES" -gt "$MAX_BYTES" ]; then
    warn "APK is ${APK_SIZE} — larger than 35MB threshold!"
    read -p "Release anyway? (y/N) " -n 1 -r
    echo ""
    [[ $REPLY =~ ^[Yy]$ ]] || err "Aborted. Check dependencies for bloat."
fi

# ── Check if tag already exists ──────────────────────────────

cd "$REPO_ROOT"
if git tag -l "$TAG" | grep -q "$TAG"; then
    err "Tag $TAG already exists. Use a different version number."
fi

# ── Generate release notes ───────────────────────────────────

PREV_TAG=$(git tag -l "${TAG_PREFIX}-v*" --sort=-v:refname | head -1 || echo "")

if [ -n "$PREV_TAG" ]; then
    CHANGES=$(git log --oneline "$PREV_TAG"..HEAD | head -20)
else
    CHANGES=$(git log --oneline -10)
}
fi

NOTES="## WhisperFlow v${VERSION}

### Changes since ${PREV_TAG:-initial}
\`\`\`
${CHANGES:-No commits found}
\`\`\`

### Features
- 🎙️ Voice-to-text with Whisper AI
- 🖥️ GPU Server mode (remote inference)
- 📱 Local mode (on-device TFLite inference)
- ⌚ Galaxy Watch relay support
- 🔘 Digital Assistant (long-press power button)
- ⌨️ IME Keyboard input method
- 📋 Tasks integration
- 🔥 Streak Tracker integration

### Build Info
- **APK Size:** ${APK_SIZE}
- **Build Time:** ${BUILD_TIME}s
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34

### Requirements
- Microphone permission
- For GPU mode: Tailscale connected to GPU server
- For Local mode: Download model in Settings tab"

# ── Create GitHub release ────────────────────────────────────

info "Creating GitHub release: $TAG"

gh release create "$TAG" \
    "$APK_PATH#$APK_NAME" \
    --title "🎙️ WhisperFlow v${VERSION}" \
    --notes "$NOTES" \
    --repo "$(gh repo view --json nameWithOwner -q .nameWithOwner)" \
    $DRAFT_FLAG

echo ""
log "Released! $TAG"
echo ""
echo "  📦 APK:     $APK_NAME ($APK_SIZE)"
echo "  🏷️  Tag:     $TAG"
echo "  🔗 URL:     $(gh release view "$TAG" --json url -q .url)"
echo ""
