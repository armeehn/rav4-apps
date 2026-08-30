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

## Open

### 0.6 — the rest of the colour, and proof on glass
Two honest gaps:

- **~2,400 hardcoded `0xAARRGGBB` literals** remain in the apps' Java. Only the 135 sites that
  already went through the shared `R.color.*` roles were converted; the literals are a
  per-app design pass, not a mechanical substitution, and rewriting them blind would ship
  twenty-six subtly broken apps.
- **Nothing here has run on the head unit.** All twenty-six compile and are signed, and the
  shared class is verified present in each dex, but "builds" is not "works": the provider read,
  the day/night re-paint and every derived colour are unproven on glass. That is one session at
  the car (tracked as RAV4-23).
