# rav4-apps roadmap

Milestones for the suite as a whole. Each app carries its own `versionName` in its manifest —
that is the app's version, not this one.

_Last verified by building all 26 apps on x, 2026-08-29._

## Landed

### 0.1 — a toolchain that does not need Gradle
`aapt2 → javac → d8 → zipalign → apksigner`, rootless, offline, ~2.5 s per app
(`template/build.sh <dir>`). Gradle was rejected because every app here is a handful of
framework-only Java files, and a build that takes two minutes to prove a colour change is a
build nobody runs twenty-six times.

### 0.2 — scope, and the apps that must never be touched
79 OEM apps pulled and decompiled. The important output is negative: `scope/protected-apps.sh`
refuses `eventcenter`, `customerui`, `canbus`/`canbus2`, `auxcamera`, `radio` and `dsp`.
Overlaying a stub on `eventcenter` takes out the reverse camera and the steering-wheel controls,
and `customerui` is not a HOME app — the home screen is a *window* the gateway inflates by name,
so a same-package stub breaks home inflation.

### 0.3 — the standalone pivot, 26 apps
The overlay route is dead for most OEM packages: PackageManager refuses a `sharedUserId` member
whose signature differs from the rest, and the platform key is confirmed unobtainable. So the
rewrites became **new packages** (`com.reveng.*`) that install as ordinary apps and are launched
from the launcher instead of replacing anything. Twenty-six of them build to signed APKs.

### 0.4 — one design system
`apps/_design/res` — shared palette, styles, drawables and icons, so the suite reads as one
product rather than twenty-six weekend projects.

### 0.5 — the launcher's colours, not ours
The suite now paints the palette the **launcher** is actually showing.

`apps/_design/src/com/reveng/design/Palette.java` reads the launcher's read-only provider
(`content://com.reveng.carlauncher.theme/active`, added in CarLauncher 0.5) and maps it onto the
design system's role names. `template/build.sh` compiles `_design/src` into every app, so the
client is shared rather than copied.

The property that made adoption safe to do in one pass, across 135 call sites, without a car to
test on: **with no launcher installed, `Palette.color(ctx, res)` returns exactly
`ctx.getColor(res)`** — the value the call site already used. A unit without the launcher looks
identical to before, and any failure at all (older launcher, missing column, provider throwing)
degrades to the shipped design instead of to a black screen.

Roles the launcher does not publish are *derived* rather than frozen, because a hairline or a
third-tier label drawn for a dark palette vanishes on a light one: `stroke`, `ripple`,
`accent_dim` and `scrim` keep the resource's own alpha and take the launcher's hue, and `text3`
is `text2` pushed toward the background so three text tiers survive any palette.

**Every app declares the provider in `<queries>`.** Without it an app targeting API 30+ cannot
see the provider *at all*: package visibility hides it, `query()` returns null, and the app
silently keeps its built-in palette. It is the one line that makes the whole feature work, and
nothing about it fails loudly — the first build of this shipped without it and looked fine
until it was put on a panel.

Verified on the emulated head unit (LXC 124, 1920x720 @240dpi), which is what caught that:

| | Clock's accent |
|---|---|
| launcher not installed | `#5B9DFF` — the design pack's own value, i.e. the fallback |
| launcher installed, before the `<queries>` fix | `#5B9DFF` — silently unthemed |
| launcher installed, after | `#2F81F7` — the launcher's Midnight primary |

`content query --uri content://com.reveng.carlauncher.theme/active` also returns the full row to
a different uid, so the provider is genuinely exported and R8 did not strip it.

## Landed (continued)

### 0.5.1 — semantic colour
The design pack gained an `error` role, mapped to the launcher's published `error`, so a theme
sets its own danger colour. The recorder's record affordance follows it.

The sound meter's green/amber/red gauge bands deliberately **do not**: they encode a measured
scale, not chrome, and a theme whose error colour is orange would collide with the amber band.

### 0.5.2 — the colours that XML resolved
`Palette.color` only helps where Java asks for a colour. A colour written in XML — a shape's
solid, a ripple, a style's `textColor` — is resolved at inflate time from the app's own
`colors.xml` and cannot follow a palette published at runtime. That is ~860 references across
the suite, and it was the real gap: with only the Java sites converted, a light theme produced a
*half-themed* screen (light ground, dark cards), which is worse than no theming at all.

`Palette.apply(activity)` walks the finished view tree and re-colours text, `ColorDrawable`
backgrounds, `GradientDrawable` solids and strokes, and ripples — **but only where the current
value is exactly a design-pack default.** That rule is what makes it safe to run over a whole
screen: it repaints what the design system painted and leaves a gauge band, a chart series or a
photo alone. With no launcher the themed value equals the default, so every replacement is a
no-op. Roles are resolved by resource *name*, so the shared class never references any app's `R`.

### 0.5.3 — it follows the theme while running
A `ContentObserver` on the provider recreates a watching activity when the palette actually
changes (compared by fingerprint, because the launcher republishes on every theme *and*
day/night change, and republishing an identical palette is allowed). `recreate()` rather than a
second walk: colours set from Java at build time — an icon tint, a paint in a custom view's
constructor — are not reachable from the view tree afterwards.

### 0.6 — proven on the panel
Switched the launcher from Midnight to Daylight on the emulated head unit and brought the
already-running Clock forward, **without restarting it**:

| sampled | Midnight | Daylight | launcher published |
|---|---|---|---|
| page background | `#0B0E11` | `#F4F6F8` | `#F6F7F8` |
| world-clock card (an XML `bg_card` drawable) | `#161B22` | `#FFFFFF` | `surface = #FFFFFF` |

The card is the one that matters: it is a shape drawable whose colour came from XML, so before
0.5.2 it stayed dark on a light ground. All 26 apps build, and `scope/check-theme-wiring.sh`
(in CI) now also asserts every Activity calls `Palette.apply`. Both of its assertions have
negative controls.

## Open

### Correction to an earlier claim
A previous revision of this file said "~2,400 hardcoded `0xAARRGGBB` literals remain". **That
number was wrong.** It came from a grep that swept `build/` directories and counted generated
`R.java` resource IDs. The real figure in app sources is **34 literals**, of which 11 were exact
design-pack values (now themed) and the rest are white, black, transparent, or deliberately
semantic — the sound meter's gauge bands. There is no large per-app colour pass outstanding.

### Still needs the car
Nothing here has run on the **real** head unit. The emulator has no vendor gateway, no root and
no car, so the day/night re-paint (it needs the illumination broadcast rather than a theme
switch) and each app's actual behaviour against real hardware remain unproven (RAV4-23).
