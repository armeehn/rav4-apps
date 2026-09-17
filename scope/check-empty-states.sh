#!/usr/bin/env bash
# Guard the shared empty state (Track D, RAV4-66).
#
# WHY. The empty state converged on four styles in apps/_design/res/values/styles.xml rather
# than an <include>: the pack reaches apps as synced styles.xml copies, an include cannot take
# an app's icon or strings, and the ten apps already agreed on the shape bar small drifts. A
# style only holds that shape while every migrated layout keeps using it, and nothing in a
# build tells you when one stops.
#
# WHAT. For every app whose layout carries @+id/empty:
#   - migrated (listed in scope/empty-state-migrated.txt): the container, title and hint must
#     carry the EmptyState styles and no view inside may re-state a padding, size, tint,
#     alpha or margin the style owns. FAIL otherwise.
#   - not yet migrated: reported, never failed; the list is the ratchet.
# The styles themselves must exist in the pack and in every app's synced copy.
set -uo pipefail
cd "$(dirname "$0")/.."
MIGRATED=scope/empty-state-migrated.txt
STYLES="EmptyState EmptyStateIcon EmptyStateTitle EmptyStateHint EmptyStateAction"
OWNED='android:(paddingLeft|paddingRight|paddingBottom|layout_marginTop|tint|alpha)='
fail=0

for s in $STYLES; do
    grep -q "name=\"$s\"" apps/_design/res/values/styles.xml || { echo "FAIL pack: style $s missing"; fail=1; }
done

pending=0
for d in apps/com.ripostelabs.*; do
    [ -f "$d/AndroidManifest.xml" ] || continue
    pkg="$(basename "$d")"
    layouts="$(grep -l '@+id/empty"' "$d"/res/layout/*.xml 2>/dev/null || true)"
    [ -n "$layouts" ] || continue

    for s in $STYLES; do
        grep -q "name=\"$s\"" "$d/res/values/styles.xml" || { echo "FAIL $pkg: $s not synced (scope/sync-design.sh)"; fail=1; }
    done

    if ! grep -qx "$pkg" "$MIGRATED" 2>/dev/null; then
        echo "todo $pkg: empty state not on the shared styles"
        pending=$((pending + 1))
        continue
    fi

    for f in $layouts; do
        # The container carries the style, and the block up to its closing tag re-states
        # nothing the styles own. The block is the container element through its first
        # </LinearLayout>; an empty state deeper than one level is not a shape this guards.
        block="$(awk '/@\+id\/empty"/{p=1} p{print} p&&/<\/LinearLayout>/{exit}' "$f")"
        echo "$block" | grep -q 'style="@style/EmptyState"' || { echo "FAIL $pkg: $f container without @style/EmptyState"; fail=1; }
        echo "$block" | grep -q 'style="@style/EmptyStateHint"' || { echo "FAIL $pkg: $f hint without @style/EmptyStateHint"; fail=1; }
        echo "$block" | grep -qE 'style="@style/EmptyStateTitle"' || { echo "FAIL $pkg: $f title without @style/EmptyStateTitle"; fail=1; }
        if echo "$block" | grep -qE "$OWNED"; then
            echo "FAIL $pkg: $f re-states an attribute the EmptyState styles own:"
            echo "$block" | grep -nE "$OWNED" | sed 's/^/    /'
            fail=1
        fi
    done
done

echo ">> empty states: $(wc -l < "$MIGRATED") migrated, $pending pending"
exit $fail
