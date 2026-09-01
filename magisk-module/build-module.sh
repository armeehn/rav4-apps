#!/usr/bin/env bash
# build-module.sh — zip the module tree into ../rav4apps-module.zip.
# Uses zip(1) when present, else python3 (some hosts have no zip binary).
# Excludes only the repo tooling (this script, pack-app.sh, README.md) —
# real Magisk scripts like customize.sh/service.sh are kept.
set -euo pipefail
MOD="$(cd "$(dirname "$0")" && pwd)"
OUT="$(dirname "$MOD")/rav4apps-module.zip"
rm -f "$OUT"
cd "$MOD"
if command -v zip >/dev/null; then
    zip -r "$OUT" . -x pack-app.sh -x build-module.sh -x README.md
else
    python3 - "$OUT" <<'PY'
import os, sys, zipfile
skip = {"pack-app.sh", "build-module.sh", "README.md"}
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk("."):
        for f in files:
            rel = os.path.relpath(os.path.join(root, f), ".")
            if rel in skip:
                continue
            z.write(rel, rel)
PY
fi
echo ">> built: $OUT"
