#!/usr/bin/env bash
# Add a replacement APK to the Magisk overlay module, mirrored to the OEM app's
# exact on-device path so Magisk overlayfs swaps it in (no /system writes).
#
#   ./pack-app.sh <package> <replacement.apk> [/on/device/path.apk]
#
# The device path is auto-resolved from ../docs/packages-raw.txt if omitted.
#
# Guards (fail closed):
#   - refuses denylisted packages (safety-critical / reflected-into apps);
#   - refuses when the replacement's package name or sharedUserId does not match
#     the OEM APK (a mismatch means Android will not treat it as the same app);
#   - warns when the OEM app is platform-signed / android.uid.system (our
#     debug-signed replacement can never inherit those privileges).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/magisk-module"
PKG="${1:?package name}"; APK="${2:?replacement apk}"
DEV="${3:-}"
[ -f "$APK" ] || { echo "no such apk: $APK" >&2; exit 1; }

# Untouchable-app denylist (is_protected).
. "$ROOT/scope/protected-apps.sh"
if is_protected "$PKG"; then
  echo "!! REFUSING $PKG — DO-NOT-REPLACE (safety-critical / reflected-into)." >&2
  echo "   Overlaying it can brick the unit or kill reverse camera / SWC / CAN." >&2
  exit 1
fi

# ---- Identity guards -------------------------------------------------------
# Resolve aapt2 from the SDK (same path the builder uses).
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
AAPT2="$SDK/build-tools/34.0.0/aapt2"

# apk_pkgname <apk> — package name via aapt2, empty on failure.
apk_pkgname() {
  [ -x "$AAPT2" ] || return 0
  "$AAPT2" dump packagename "$1" 2>/dev/null | tr -d '[:space:]'
}
# apk_shareduid <apk> — sharedUserId value, empty if none/unknown.
apk_shareduid() {
  [ -x "$AAPT2" ] || return 0
  "$AAPT2" dump xmltree --file AndroidManifest.xml "$1" 2>/dev/null \
    | sed -n 's/.*sharedUserId[^"]*"\([^"]*\)".*/\1/p' | head -1
}

if [ ! -x "$AAPT2" ]; then
  echo "!! aapt2 not found ($AAPT2) — cannot verify APK identity; refusing." >&2
  echo "   Set ANDROID_SDK_ROOT or install build-tools 34.0.0, then re-run." >&2
  exit 1
fi

# Replacement identity.
rep_pkg="$(apk_pkgname "$APK")"
rep_uid="$(apk_shareduid "$APK")"
[ -n "$rep_pkg" ] || { echo "!! could not read replacement package name from $APK" >&2; exit 1; }
if [ "$rep_pkg" != "$PKG" ]; then
  echo "!! replacement package '$rep_pkg' != requested '$PKG' — refusing." >&2
  exit 1
fi

# Compare against the pulled OEM APK when we have it.
OEM_APK="$ROOT/apks/$PKG.apk"
if [ -f "$OEM_APK" ]; then
  oem_pkg="$(apk_pkgname "$OEM_APK")"
  oem_uid="$(apk_shareduid "$OEM_APK")"
  if [ -n "$oem_pkg" ] && [ "$oem_pkg" != "$rep_pkg" ]; then
    echo "!! OEM package '$oem_pkg' != replacement '$rep_pkg' — refusing." >&2
    exit 1
  fi
  if [ "${oem_uid:-}" != "${rep_uid:-}" ]; then
    echo "!! sharedUserId mismatch — OEM='${oem_uid:-<none>}' replacement='${rep_uid:-<none>}'; refusing." >&2
    echo "   The OS keys app identity on (package + sharedUserId); a mismatch is a different app." >&2
    exit 1
  fi
  if [ "${oem_uid:-}" = "android.uid.system" ]; then
    echo "!! WARN: OEM app is android.uid.system (platform-signed)." >&2
    echo "   A debug-signed overlay CANNOT hold its signature/privileged perms;" >&2
    echo "   privileged flows will silently fail. Proceed only if the app is UI-only." >&2
  fi
else
  echo ">> note: no OEM APK at $OEM_APK — skipping OEM identity cross-check (run scope-apps.sh to pull it)."
fi

# ---- Resolve on-device path ------------------------------------------------
if [ -z "$DEV" ]; then
  DEV="$(grep "=$PKG\$" "$ROOT/docs/packages-raw.txt" 2>/dev/null | head -1 | sed 's/=[^=]*$//')"
fi
[ -n "$DEV" ] || { echo "could not resolve on-device path for $PKG; pass it as arg 3" >&2; exit 1; }
echo ">> $PKG  ->  $DEV"

# Magisk mounts a module's `system/` subtree over the root. /product, /vendor,
# /system_ext are reached on-device via /system/product, /system/vendor, ... so
# EVERY dest must live under $MOD/system/... — never $MOD/product/... (which
# Magisk would never mount). Strip a leading /system, then place under system/.
rel="${DEV#/system}"                 # /system/... -> /...  ;  /product/... unchanged
dest="$MOD/system${rel}"
mkdir -p "$(dirname "$dest")"
cp "$APK" "$dest"
echo ">> placed at module path: ${dest#$MOD/}"
# (Magisk mirrors the module tree over the real fs; matching the OEM path replaces it.)
# Exclude only the authoring helper + README; customize.sh and META-INF/* MUST ship.
echo ">> zip the module:  (cd $MOD && zip -r ../rav4apps-module.zip . -x 'pack-app.sh' -x 'README.md')"
