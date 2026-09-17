# Track D inventory: what the 28 apps actually share

Measured 2026-09-17 on main `a6a6fc6`, by grep and md5 over `apps/com.ripostelabs.*`
(build dirs excluded). Scope step 1 of RAV4-66; no code changed.

## Verdict

There is nothing left to extract verbatim. Zero Java files and zero layouts are
byte-identical across apps, and the shared pack `apps/_design` already holds the
six classes every app compiles in (`Palette`, `IconRole`, `MediaCitizen`,
`PaintLedger`, `AlarmAudio`, `WebAudio`; 28 of 28 apps call `Palette`). What the
apps share is *shape*, re-typed per app with small drifts. Two shapes are worth
one module each; the rest is not.

## The two candidates

### 1. Permission gate (16 apps, 62 call sites)

`checkSelfPermission` / `requestPermissions` / `onRequestPermissionsResult`
hand-rolled in bluetooth, calendar, clock, contacts, files, gps, installer,
lamp, music, photos, projection, recorder, soundmeter, speedometer, video,
weather. Nine of them pair it with a `grant` button and a `status` line in the
layout; the others fold the denial into the empty state. Same three branches
every time: granted → load, denied → explain + button, result → repeat.

Shape of a shared `PermissionGate` (in `_design`): one class taking the
permission list, a view to reveal on denial, and a `Runnable` for "granted";
it owns the request code and `onRequestPermissionsResult` plumbing. Testable
without Android for the branch logic if the check is injected.

### 2. Empty state (10 apps, 3 id shapes)

| shape | apps |
|---|---|
| `empty` + `empty_hint` + `empty_text` | bluetooth, music, photos, recorder, video |
| `empty` + `empty_hint` + `empty_icon` + `empty_text` | files, installer |
| `empty` + `empty_hint` + `empty_icon` + `empty_title` | contacts, tasks (notes: no icon) |

One `<include layout="@layout/empty_state">` with icon, title, hint, all
optional, plus a `showEmpty(boolean)` helper. The three shapes collapse into it
without a visual change if the icon and title stay optional.

## Not candidates

- Title bars: only browser, news and video draw a back arrow (`id/back`); the
  rest rely on the launcher's chrome. Nothing to share.
- List rows: `list` in 8 apps but each row layout is app-specific; no two match.
- `Ui.java` (programmatic dp/text/pill helpers) exists in clock only, 63 `Ui.dp`
  calls. It is a one-app convenience, not a suite pattern, until a second app
  wants it.
- Theme wiring: already shared (Palette) and guarded by
  `scope/check-theme-wiring.sh`, which asserts the `<queries>` entry the issue
  warns about.

## Tests today

Four `test/` directories: `_design` (`IconRoleTest`, `PaintLedgerTest`) and
three apps. `scope/run-tests.sh` runs them with no framework. A shared
`PermissionGate` and `EmptyState` would each ship a test there, which makes the
shared thing the tested thing, as the issue asks.

## Order

1. `EmptyState` include + helper in `_design`, migrate the 10 apps one PR each
   (layout-only, lowest risk, visible in the emulator).
2. `PermissionGate` in `_design`, migrate the 16 apps one PR each.
3. Nothing else from this list.

Signing is untouched by either: no manifest or key change, so no reinstall on
the car.
