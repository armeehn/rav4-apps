#!/usr/bin/env bash
# For each OEM app found by scope-apps.sh, scaffold a rewrite project under apps/<pkg>/
# from the template, pre-loaded with the OEM's own extracted strings so fixing the
# typos/copy is a direct diff. Also carries the OEM package name so the rewrite drops
# into the same launcher/intent slot.
#
#   ./generate-skeletons.sh              # from docs/candidates.txt (scope output)
#   ./generate-skeletons.sh com.foo ...  # explicit package list
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TPL="$ROOT/template"; APPS="$ROOT/apps"; DEC="$ROOT/decompiled"
mkdir -p "$APPS"

pkgs=()
if [ "$#" -gt 0 ]; then pkgs=("$@")
elif [ -f "$ROOT/docs/candidates.txt" ]; then
  while IFS= read -r l; do [ -n "$l" ] && pkgs+=("${l##*=}"); done < "$ROOT/docs/candidates.txt"
else
  echo "no candidates.txt yet — run scope-apps.sh first, or pass package names." >&2; exit 1
fi

for pkg in "${pkgs[@]}"; do
  [ -z "$pkg" ] && continue
  dst="$APPS/$pkg"
  if [ -d "$dst" ]; then echo "skip (exists): $pkg"; continue; fi
  echo ">> scaffolding $pkg"
  mkdir -p "$dst/res/values" "$dst/res/layout" "$dst/src/${pkg//.//}"
  # manifest with the OEM package name
  sed "s/com\.rav4apps\.template/$pkg/g" "$TPL/AndroidManifest.xml" > "$dst/AndroidManifest.xml"
  # MainActivity in the OEM package namespace
  sed "s/com\.rav4apps\.template/$pkg/g" "$TPL/src/com/rav4apps/template/MainActivity.java" \
    > "$dst/src/${pkg//.//}/MainActivity.java"
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
