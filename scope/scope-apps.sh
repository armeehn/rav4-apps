#!/usr/bin/env bash
# Scope the GT6 head unit's built-in (OEM/vendor) apps for rewrite.
#
# Usage:
#   ./scope-apps.sh                 # device over USB
#   ./scope-apps.sh 100.x.y.z       # device over tailnet (adb connect :5555)
#   ./scope-apps.sh 172.20.10.10    # device over hotspot LAN
#   ./scope-apps.sh --offline       # no car: reclassify from apks/ + docs/candidates.txt
#
# All read-only on the car: enumerate -> filter to OEM apps -> pull -> decompile
# resources -> classify EASY (self-contained) vs HW (touches the vehicle).
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"   # adb/java/jadx live here on some hosts

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APKS="$ROOT/apks"; DEC="$ROOT/decompiled"; DOCS="$ROOT/docs"
mkdir -p "$APKS" "$DEC" "$DOCS"
APKTOOL="$HOME/.local/opt/apktool.jar"

# Untouchable-app denylist (is_protected).
. "$(dirname "$0")/protected-apps.sh"

ADB=adb
OFFLINE="no"
if [ "${1:-}" = "--offline" ]; then OFFLINE="yes"; shift; fi
TARGET="${1:-}"

# Offline: the classifier only needs the cached APKs and the candidate list, so a
# rule change can be re-run at the desk instead of waiting for the next drive.
if [ "$OFFLINE" = "yes" ]; then
  [ -s "$DOCS/candidates.txt" ] || { echo "!! --offline needs docs/candidates.txt from a previous device run." >&2; exit 1; }
  SER="offline"
  dsh() { :; }
elif [ -n "$TARGET" ]; then
  case "$TARGET" in *:*) EP="$TARGET";; *) EP="$TARGET:5555";; esac
  echo ">> adb connect $EP"; $ADB connect "$EP" >/dev/null 2>&1 || true; SER="$EP"
else
  SER="$($ADB devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
fi
if [ "$OFFLINE" = "no" ] && { [ -z "${SER:-}" ] || ! $ADB -s "$SER" get-state >/dev/null 2>&1; }; then
  SER="$($ADB devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
fi
if [ -z "${SER:-}" ]; then
  echo "!! no online device. Start the car, confirm adb is up, then re-run." >&2
  $ADB devices -l >&2; exit 1
fi
echo ">> using device: $SER"
[ "$OFFLINE" = "yes" ] || dsh() { $ADB -s "$SER" shell "$@"; }

echo ">> fingerprint: $(dsh getprop ro.build.fingerprint 2>/dev/null)"
echo ">> android:     $(dsh getprop ro.build.version.release 2>/dev/null)"

if [ "$OFFLINE" = "no" ]; then
  echo ">> enumerating packages..."
  dsh pm list packages -f 2>/dev/null | sed 's/^package://' > "$DOCS/packages-raw.txt"
fi

grep -E '^/(system|vendor|product|system_ext|oem|odm)/' "$DOCS/packages-raw.txt" \
  | grep -vE '=com\.android\.(inputmethod|internal|providers|systemui|settings|documentsui|externalstorage|shell|se|carrier|cts|egg|traceur|dreams|bips|bookmark|captiveportal|emergency|htmlviewer|keychain|location|managedprovisioning|mms|pacprocessor|phone|proxyhandler|server|sharedstoragebackup|statementservice|vpndialogs|wallpaper)' \
  | grep -vE '=com\.google\.android\.(gms|gsf|packageinstaller|webview|ext|networkstack|permission|configupdater|onetimeinitializer|partnersetup)' \
  | grep -vE '=android$|=com\.android\.(cellbroadcast|nfc)' \
  > "$DOCS/candidates.txt" || true
N=$(wc -l < "$DOCS/candidates.txt" | tr -d ' ')
echo ">> $N candidate OEM/vendor apps"

REPORT="$DOCS/scope-report.md"
{
  echo "# GT6 head unit - built-in app scope report"; echo
  if [ "$OFFLINE" = "yes" ]; then echo "Offline re-run from cached \`apks/\` ($(date +%F))"; else echo "Device: \`$SER\`  ·  $(dsh getprop ro.build.fingerprint 2>/dev/null)"; fi; echo
  echo "| Package | APK | class | gateway | vendor svc | notes |"
  echo "|---|---|---|---|---|---|"
} > "$REPORT"

pulln=0
while IFS= read -r line; do
  [ -z "$line" ] && continue
  path="${line%=*}"; pkg="${line##*=}"; [ -z "$pkg" ] && continue
  echo "   -- $pkg"
  local_apk="$APKS/$pkg.apk"
  if [ ! -f "$local_apk" ]; then
    [ "$OFFLINE" = "yes" ] && { echo "      (no cached apk)"; continue; }
    $ADB -s "$SER" pull "$path" "$local_apk" >/dev/null 2>&1 || { echo "      (pull failed)"; continue; }
  fi
  sz=$(du -h "$local_apk" 2>/dev/null | cut -f1)
  outdir="$DEC/$pkg"
  # Decode resources+manifest. Track success: a decode failure must NOT be
  # allowed to fall through as EASY (an undecodable/corrupt APK is unknown, and
  # possibly hardware-wired — e.g. the truncated canbus2). Classified UNKNOWN.
  decode_ok="yes"
  if [ ! -f "$outdir/AndroidManifest.xml" ]; then
    java -jar "$APKTOOL" d -s -f -o "$outdir" "$local_apk" >/dev/null 2>&1 || decode_ok="no"
    [ -f "$outdir/AndroidManifest.xml" ] || decode_ok="no"
  fi
  # Classify. This unit has NO AOSP car framework (android.car); vehicle
  # integration runs through the szchoiceway gateway. Only the DECODED MANIFEST
  # decides HW: system sharedUserId, the Choiceway broadcast permission, a
  # <uses-permission> on a vendor permission, or a <uses-library> on a vendor
  # jar. The earlier `strings` sweep of the whole APK is gone: a media player
  # that merely names android.car in an unused constant was flagged HW, and a
  # false HW hides an EASY rewrite behind "RE first". `strings` still feeds the
  # advisory vendor-svc column, which never changes the class.
  gw="no"; vend="no"; manifest="$outdir/AndroidManifest.xml"
  if [ -f "$manifest" ]; then
    grep -qE 'sharedUserId="android\.uid\.system"' "$manifest" 2>/dev/null && gw="YES"
    grep -E '<uses-(permission|library)' "$manifest" 2>/dev/null \
      | grep -qE 'android:name="(com\.szchoiceway|com\.choiceway|vendor\.|android\.car)' && gw="YES"
  fi
  strings "$local_apk" 2>/dev/null \
    | grep -qE 'com\.(szchoiceway|choiceway|toyota|denso|panasonic|harman|qti|qualcomm)\.|vendor\.' \
    && vend="YES"

  cls="EASY"; note="self-contained UI"
  [ "$gw" = "YES" ] && { cls="HW"; note="talks to the car gateway - RE first"; }
  # A failed decode is never EASY: mark UNKNOWN so it is inspected by hand.
  [ "$decode_ok" = "no" ] && { cls="UNKNOWN"; note="decode failed (corrupt/truncated?) - inspect"; }
  # Denylist wins: never scaffold a rewrite for a safety-critical / reflected app.
  if is_protected "$pkg"; then
    cls="DO-NOT-REPLACE"; note="safety-critical / reflected-into - overlay refused"
  fi
  echo "| \`$pkg\` | ${sz:-?} | $cls | $gw | $vend | $note |" >> "$REPORT"
  pulln=$((pulln+1))
done < "$DOCS/candidates.txt"

{
  echo; echo "_$pulln apps pulled + decompiled. EASY = clean-room rewrite; HW = reverse-engineer the gateway interface first; DO-NOT-REPLACE = safety-critical/reflected, overlay refused; UNKNOWN = decode failed, inspect by hand._"
  echo; echo "Next: \`jadx -d decompiled-full/<pkg> apks/<pkg>.apk\` for full sources; inspect \`decompiled/<pkg>/res/values*/strings.xml\` for copy fixes."
} >> "$REPORT"
echo; echo ">> done. report: $REPORT"; cat "$REPORT"
