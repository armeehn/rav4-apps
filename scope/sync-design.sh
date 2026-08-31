#!/usr/bin/env bash
# Propagate the shared design pack (apps/_design/res) into every app.
#
# template/build.sh compiles only an app's own res/, so the pack's resources exist as
# per-app copies. This script is the single way those copies are made:
#   - values/colors.xml, values/styles.xml and res/font/* are copied outright — app-local
#     additions belong in separate *_app.xml files, never in the shared copies;
#   - the shape drawables every app needs are copied to all apps (styles.xml references
#     btn_ghost via the theme's buttonStyle);
#   - any other pack drawable an app already carries (icons) is refreshed from the master.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="apps/_design/res"

SHAPES="bg_card.xml bg_card_grad.xml bg_field.xml btn_accent.xml btn_fab.xml btn_ghost.xml btn_icon.xml"

for d in apps/com.reveng.*; do
    mkdir -p "$d/res/values" "$d/res/drawable" "$d/res/font"
    cp "$SRC/values/colors.xml" "$SRC/values/styles.xml" "$d/res/values/"
    cp "$SRC"/font/* "$d/res/font/"
    for f in $SHAPES; do
        cp "$SRC/drawable/$f" "$d/res/drawable/"
    done
    for f in "$d"/res/drawable/*.xml; do
        b="$(basename "$f")"
        if [ -f "$SRC/drawable/$b" ]; then
            cp "$SRC/drawable/$b" "$f"
        fi
    done
done
echo ">> design pack synced into $(ls -d apps/com.reveng.* | wc -l) apps"
