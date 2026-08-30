#!/usr/bin/env bash
# deploy-module.sh — thin client-side deploy. Building happens on a build host;
# this only fetches the built Magisk module from it and installs it on the unit.
#
#   ./deploy-module.sh                 # unit over USB
#   ./deploy-module.sh 10.0.0.5        # unit over the network (adb :5555)
#   ./deploy-module.sh --reboot [ip]   # reboot the unit after install
#
# Env: RAV4_SERVER — an ssh/rsync target holding a built rav4-apps checkout.
#      Required; there is no sensible default for someone else's machine.
set -euo pipefail

SERVER="${RAV4_SERVER:-}"
[ -n "$SERVER" ] || {
    echo "!! RAV4_SERVER is not set."
    echo "   Point it at the host that built the module, e.g."
    echo "     RAV4_SERVER=user@buildhost ./deploy-module.sh"
    echo "   Or build locally and install the zip with magisk --install-module."
    exit 1
}
ROOT="$(cd "$(dirname "$0")" && pwd)"
ZIP=rav4apps-module.zip

REBOOT=; TARGET=
for a in "$@"; do
    case $a in
        --reboot) REBOOT=1 ;;
        *)        TARGET=$a ;;
    esac
done

echo ">> fetching built module from $SERVER..."
rsync -h --partial "$SERVER:rav4-apps/$ZIP" "$ROOT/"

ADB=(adb)
if [ -n "$TARGET" ]; then
    case "$TARGET" in *:*) EP=$TARGET ;; *) EP=$TARGET:5555 ;; esac
    echo ">> adb connect $EP"
    adb connect "$EP" >/dev/null 2>&1 || true
    ADB=(adb -s "$EP")
fi
"${ADB[@]}" get-state >/dev/null 2>&1 || {
    echo "!! no adb device (car awake? USB plugged in / network up?)"; exit 1; }

"${ADB[@]}" push "$ROOT/$ZIP" "/sdcard/Download/$ZIP"
"${ADB[@]}" shell su -c "magisk --install-module /sdcard/Download/$ZIP"
echo ">> module installed."
if [ -n "$REBOOT" ]; then
    "${ADB[@]}" reboot
    echo ">> unit rebooting — overlay active on next boot."
else
    echo ">> reboot the unit to activate ('adb reboot' or ignition cycle)."
fi
