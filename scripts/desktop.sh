#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ "$(uname -s)" != Darwin ]]; then
    echo "This desktop receiver currently uses macOS usbmuxd and IOKit." >&2; exit 1
fi
USB_HELPER="$ROOT/desktop/build/carlink-usb"
if [[ ! -x "$USB_HELPER" || "$ROOT/desktop/native/usb.c" -nt "$USB_HELPER" ]]; then
    if ! pkg-config --exists libimobiledevice-1.0 2>/dev/null; then
        echo "CarLink Desk needs libimobiledevice: brew install libimobiledevice" >&2; exit 1
    fi
    mkdir -p "$ROOT/desktop/build"
    xcrun clang -O2 "$ROOT/desktop/native/usb.c" $(pkg-config --cflags --libs libimobiledevice-1.0 libplist-2.0) \
        -framework IOKit -framework CoreFoundation -o "$USB_HELPER"
fi
case "${1:-run}" in
    devices) exec "$USB_HELPER" devices ;;
    test) exec "$ROOT/gradlew" -p "$ROOT/desktop" test ;;
    build) exec "$ROOT/gradlew" -p "$ROOT/desktop" installDist ;;
    run)
        "$ROOT/gradlew" -p "$ROOT/desktop" installDist
        export CARLINK_DESKTOP_ROOT="$ROOT/desktop"
        export CARLINK_USB_HELPER="$USB_HELPER"
        export DIPLAY_AUTH_ASSETS_DIR="${DIPLAY_AUTH_ASSETS_DIR:-$ROOT/.private/runtime-assets}"
        exec "$ROOT/desktop/build/install/carlink-desktop/bin/carlink-desktop"
        ;;
    *) echo "usage: scripts/desktop.sh [run|build|test|devices]" >&2; exit 2 ;;
esac
