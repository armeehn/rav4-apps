#!/usr/bin/env bash
# Add a replacement APK to the Magisk overlay module, mirrored to the OEM app's
# exact on-device path so Magisk overlayfs swaps it in (no /system writes).
#
#   ./pack-app.sh <package> <replacement.apk> [/on/device/path.apk]
#
# The device path is auto-resolved from ../docs/packages-raw.txt if omitted.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/magisk-module"
PKG="${1:?package name}"; APK="${2:?replacement apk}"
DEV="${3:-}"
[ -f "$APK" ] || { echo "no such apk: $APK" >&2; exit 1; }
if [ -z "$DEV" ]; then
  DEV="$(grep "=$PKG\$" "$ROOT/docs/packages-raw.txt" 2>/dev/null | head -1 | sed 's/=[^=]*$//')"
fi
[ -n "$DEV" ] || { echo "could not resolve on-device path for $PKG; pass it as arg 3" >&2; exit 1; }
echo ">> $PKG  ->  $DEV"
dest="$MOD/system${DEV#/system}"          # for /system/... paths
case "$DEV" in /system/*) dest="$MOD/system${DEV#/system}";;
               *)         dest="$MOD${DEV}";; esac  # /product, /vendor kept as-is under module root
mkdir -p "$(dirname "$dest")"
cp "$APK" "$dest"
echo ">> placed at module path: ${dest#$MOD/}"
# (Magisk mirrors the module tree over the real fs; matching the OEM path replaces it.)
echo ">> zip the module:  (cd $MOD && zip -r ../rav4apps-module.zip . -x '*.sh')"
