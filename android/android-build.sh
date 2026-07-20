#!/bin/zsh
#
# android-build.sh - Helper for building LanSync's Android app from CLI
# (Android project on JohnDovey external drive)
#
# Usage examples:
#   ./android-build.sh                  # assembleDebug (default)
#   ./android-build.sh assembleRelease
#   ./android-build.sh installDebug
#   ./android-build.sh clean
#   ./android-build.sh tasks            # list available tasks
#
# Make sure the JohnDovey drive is mounted.

set -e

# Activate JohnDovey environment (quietly)
if [[ -f ~/source-john-dovey.sh ]]; then
    source ~/source-john-dovey.sh --quiet
fi

# Move to project root (directory containing this script)
cd "$(dirname "$0")"

# Ensure wrapper is executable
chmod +x gradlew 2>/dev/null || true

# Default to assembleDebug if no arguments. All args (tasks + gradle flags) are
# passed through.
if [ $# -eq 0 ]; then
    set -- "assembleDebug"
fi

echo "Building LanSync Android app..."
echo "   Args: $@"
echo "   ANDROID_HOME=$ANDROID_HOME"
echo ""

./gradlew "$@"

echo ""
echo "Build finished"
