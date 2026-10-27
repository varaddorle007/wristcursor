#!/usr/bin/env bash
# Builds "Drift Receiver.app" with its own trimmed Java runtime, so no Java install is needed.
# Run from the project root: ./receiver/package-mac.sh
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
./gradlew :receiver:jar -q

OUT="receiver/build/mac"
APP="$OUT/Drift Receiver.app"
rm -rf "$OUT"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/app"

# A trimmed runtime when the JDK can build one; otherwise copy the whole JDK
# (Android Studio's bundled JDK can't be trimmed, and is about 100 MB).
if ! "$JAVA_HOME/bin/jlink" \
  --add-modules java.desktop,java.prefs \
  --strip-debug --no-header-files --no-man-pages \
  --output "$APP/Contents/Resources/runtime" >/dev/null 2>&1; then
  rm -rf "$APP/Contents/Resources/runtime"
  ditto "$JAVA_HOME" "$APP/Contents/Resources/runtime"
fi

cp receiver/build/libs/drift-receiver.jar "$APP/Contents/Resources/app/"

cat > "$APP/Contents/MacOS/drift-receiver" <<'EOF'
#!/bin/bash
HERE="$(cd "$(dirname "$0")/.." && pwd)"
exec "$HERE/Resources/runtime/bin/java" -Dapple.awt.application.name="Drift Receiver" \
  -jar "$HERE/Resources/app/drift-receiver.jar"
EOF
chmod +x "$APP/Contents/MacOS/drift-receiver"

cat > "$APP/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>Drift Receiver</string>
  <key>CFBundleDisplayName</key><string>Drift Receiver</string>
  <key>CFBundleIdentifier</key><string>com.drift.receiver</string>
  <key>CFBundleVersion</key><string>1.0.0</string>
  <key>CFBundleShortVersionString</key><string>1.0.0</string>
  <key>CFBundleExecutable</key><string>drift-receiver</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>LSMinimumSystemVersion</key><string>11.0</string>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
EOF

echo "Built: $APP"
