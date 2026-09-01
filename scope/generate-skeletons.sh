#!/usr/bin/env bash
# For each OEM app found by scope-apps.sh, scaffold a STANDALONE rewrite under
# apps/com.ripostelabs.<leaf>/ (e.g. com.szchoiceway.videoplayer -> com.ripostelabs.videoplayer),
# pre-loaded with the OEM's own extracted strings so fixing the typos/copy is a direct
# diff. The OEM package name is used only to find its decompiled strings: a rewrite
# never takes the vendor's package (PackageManager refuses a sharedUserId member with a
# foreign signature, and the platform key is unobtainable — see README, "They are
# standalone apps, not overlays").
#
#   ./generate-skeletons.sh              # from docs/candidates.txt (scope output)
#   ./generate-skeletons.sh com.foo ...  # explicit package list
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TPL="$ROOT/template"; APPS="$ROOT/apps"; DEC="$ROOT/decompiled"
mkdir -p "$APPS"

# Untouchable-app denylist (is_protected).
. "$(dirname "$0")/protected-apps.sh"

pkgs=()
if [ "$#" -gt 0 ]; then pkgs=("$@")
elif [ -f "$ROOT/docs/candidates.txt" ]; then
  while IFS= read -r l; do [ -n "$l" ] && pkgs+=("${l##*=}"); done < "$ROOT/docs/candidates.txt"
else
  echo "no candidates.txt yet — run scope-apps.sh first, or pass package names." >&2; exit 1
fi

for pkg in "${pkgs[@]}"; do
  [ -z "$pkg" ] && continue
  # Refuse denylisted packages: replacing them can brick the unit or kill a
  # safety function (reverse camera, SWC, CAN). See scope/protected-apps.sh.
  if is_protected "$pkg"; then
    echo "!! REFUSING $pkg — DO-NOT-REPLACE (safety-critical / reflected-into)" >&2
    continue
  fi
  # Our own package: the leaf of the OEM name under com.ripostelabs.
  new="com.ripostelabs.$(printf '%s' "${pkg##*.}" | tr 'A-Z' 'a-z')"
  dst="$APPS/$new"
  if [ -d "$dst" ]; then echo "skip (exists): $new"; continue; fi
  echo ">> scaffolding $new (from $pkg)"
  mkdir -p "$dst/res/values" "$dst/res/layout" "$dst/src/${new//.//}"
  sed "s/com\.rav4apps\.template/$new/g" "$TPL/AndroidManifest.xml" > "$dst/AndroidManifest.xml"
  sed "s/com\.rav4apps\.template/$new/g" "$TPL/src/com/rav4apps/template/MainActivity.java" \
    > "$dst/src/${new//.//}/MainActivity.java"
  cp "$TPL/res/layout/activity_main.xml" "$dst/res/layout/"
  # seed strings: prefer the OEM's own extracted copy (so typos are visible & fixable)
  oem_strings="$DEC/$pkg/res/values/strings.xml"
  if [ -f "$oem_strings" ]; then
    cp "$oem_strings" "$dst/res/values/strings.xml"
    echo "   seeded OEM strings ($(grep -c '<string' "$oem_strings") entries) — fix typos here"
  else
    cp "$TPL/res/values/strings.xml" "$dst/res/values/"
  fi
  # per-app build shim
  printf '#!/usr/bin/env bash\nexec "%s/template/build.sh" "%s"\n' "$ROOT" "$dst" > "$dst/build.sh"
  chmod +x "$dst/build.sh"
done
echo ">> done. ${#pkgs[@]} package(s) processed. Build one: apps/<pkg>/build.sh"
