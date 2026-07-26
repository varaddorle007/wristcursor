#!/bin/zsh
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")" && pwd)
APP_DIR="$ROOT_DIR/build/WristCursorReceiver.app"
BIN_DIR="$APP_DIR/Contents/MacOS"
mkdir -p "$BIN_DIR"
swiftc -O -framework Cocoa -framework CoreBluetooth -framework ApplicationServices \
  "$ROOT_DIR/WristCursorReceiver.swift" -o "$BIN_DIR/WristCursorReceiver"
cp "$ROOT_DIR/Info.plist" "$APP_DIR/Contents/Info.plist"
echo "$APP_DIR"
