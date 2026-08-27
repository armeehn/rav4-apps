#!/usr/bin/env bash
# deploy-module.sh — thin laptop-side deploy. All building happens on server
# x; this only fetches the built Magisk module and installs it on the unit.
#
#   ./deploy-module.sh                 # unit over USB
#   ./deploy-module.sh 100.x.y.z      # unit over the tailnet
#   ./deploy-module.sh --reboot [ip]  # reboot the unit after install
#
# Env: RAV4_SERVER (default x.hq.ripostelabs.xyz)
set -euo pipefail

SERVER="${RAV4_SERVER:-x.hq.ripostelabs.xyz}"
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
    echo "!! no adb device (car awake? USB plugged / tailscale up?)"; exit 1; }

"${ADB[@]}" push "$ROOT/$ZIP" "/sdcard/Download/$ZIP"
"${ADB[@]}" shell su -c "magisk --install-module /sdcard/Download/$ZIP"
echo ">> module installed."
if [ -n "$REBOOT" ]; then
    "${ADB[@]}" reboot
    echo ">> unit rebooting — overlay active on next boot."
else
    echo ">> reboot the unit to activate ('adb reboot' or ignition cycle)."
fi
