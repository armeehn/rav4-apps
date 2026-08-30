# rav4-apps

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![CI](https://github.com/armeehn/rav4-apps/actions/workflows/ci.yml/badge.svg)](https://github.com/armeehn/rav4-apps/actions/workflows/ci.yml)

Twenty-six clean-room replacements for the built-in apps on a Choiceway / AiNavi
**GT6-EAU** Android 13 head unit — the clock, calculator, files, notes, radio,
music and so on. They are plain Android apps, built without Gradle, and they pick
up their colours from the [Car Launcher](https://github.com/armeehn/device-reveng)
when it is installed.

Companion to **[armeehn/device-reveng](https://github.com/armeehn/device-reveng)**,
which holds the launcher and the reverse-engineering notes for the same unit.

---

## They are standalone apps, not overlays

An earlier plan replaced each OEM app **in place** via a Magisk systemless overlay,
reusing the vendor's own package name. That approach is dead, and it is worth
knowing why before you propose it again:

- PackageManager refuses a member of a `sharedUserId` group whose signature differs
  from the rest, and most OEM apps here share `android.uid.system`.
- The vendor platform signing key is confirmed unobtainable.
- Stubbing a package the vendor gateway reflects on takes out the reverse camera
  and the steering-wheel controls with it.

So each rewrite ships as an ordinary package under its own name
(`com.ripostelabs.clock`, `com.ripostelabs.files`, …), installs like any other APK,
and leaves the OEM app alone. You choose which one to open.

The denylist still matters for anything that *would* have been overlaid.
`scope/protected-apps.sh` refuses to scaffold over `eventcenter`, `customerui`,
`canbus`/`canbus2`, `auxcamera`, `radio` and `dsp` — the gateway and safety-critical
packages.

---

## ⚠️ Read this first

- Personal research on hardware I own, shared in case it helps other owners.
- **Not affiliated** with Choiceway, AiNavi, Toyota or Qualcomm. All trademarks
  belong to their owners.
- **No vendor APKs, firmware or decompiled vendor sources are redistributed here.**
  `apks/` and `decompiled/` are git-ignored working directories you populate from
  your own unit.
- These are side-loaded APKs signed with a throwaway debug key. Nothing here needs
  root, and nothing here reflashes anything — but the unit it targets is a rooted
  one, and the usual caveats about aftermarket head units apply.

---

## Install

Build the APKs (below), then install what you want:

```bash
adb install -r apps/com.ripostelabs.clock/app-debug.apk
```

Or all of them:

```bash
adb install-multiple apps/com.ripostelabs.*/app-debug.apk
```

They appear in the launcher's app drawer alongside everything else. The Car
Launcher groups them together and its Setup Doctor reports which are missing.

> Every app is signed with the repo's throwaway `debug.keystore`, which is
> git-ignored and minted per build host. APKs from two different machines will not
> install over each other — uninstall first, or build the whole set in one place.

---

## Build

No Gradle. Each app is a handful of framework-only Java files, and the pipeline is
`aapt2 → javac → d8 → zipalign → apksigner`. About 2.5 s per app, under a minute
for all 26.

Requirements:

- **A real JDK 17.** `d8` from build-tools 34 crashes on class files from a newer
  JDK even with `--release 17`. `build.sh` looks for a genuine 17 and only falls
  back to `--release 17` if it cannot find one.
- **Android SDK** with platform **android-33** (the car's API level) and
  build-tools **34.0.0**. Point `ANDROID_SDK_ROOT` at it.

```bash
export ANDROID_SDK_ROOT=~/Android/Sdk
template/build.sh apps/com.ripostelabs.clock     # one app
for d in apps/com.ripostelabs.*; do template/build.sh "$d"; done   # all of them
```

Each build drops a signed `app-debug.apk` in the app's own directory.

### Check the theme wiring

```bash
scope/check-theme-wiring.sh
```

Run this after touching a manifest. It is not optional — see below for why a
failure here is invisible on the panel.

---

## How theming works, and how it breaks silently

The launcher publishes its live palette on a read-only content provider at
`com.ripostelabs.carlauncher.theme`. Every app reads it through
`apps/_design/src/com/ripostelabs/design/Palette.java`, which `build.sh` compiles
into each app.

`Palette.color(ctx, res)` returns exactly `ctx.getColor(res)` when the launcher is
absent, so an app is fully functional standalone. **That graceful fallback is also
the failure mode**, which is what makes this dangerous:

> Under API 30+ package visibility, an app that does not declare
> ```xml
> <queries><provider android:authorities="com.ripostelabs.carlauncher.theme" /></queries>
> ```
> cannot see the provider at all. `query()` returns null and the app quietly keeps
> its own colours — **byte-identical to "the launcher isn't installed."** Nothing
> throws, nothing fails a build, and the only signal is
> `ActivityThread: Failed to find provider info` in that app's log.

`scope/check-theme-wiring.sh` is the only thing that tells those two states apart.
It runs in CI and it has a negative control.

Two more rules it enforces:

- **Every Activity must call `Palette.apply(this)`.** Colours set in XML — a shape's
  solid, a ripple, a style's `textColor` — are resolved at inflate time and cannot
  follow a runtime palette. `Palette.apply` walks the finished view tree and
  replaces only values that are exactly a design-pack default. Skip it and you get
  a *half*-themed screen, which looks worse than an unthemed one.
- **Anything that plays or records audio must go through
  `com.ripostelabs.design.MediaCitizen`** (audio focus + MediaSession + media
  buttons). Without it an app plays over the radio, will not duck for a navigation
  prompt, is invisible to the launcher's now-playing card, and the steering-wheel
  media keys do nothing. None of that fails a build or is visible on a desk.

---

## Layout

| Path | What |
|---|---|
| `apps/<pkg>/` | One clean-room app per OEM package |
| `apps/_design/` | Shared design pack — `Palette`, `MediaCitizen`; compiled into every app |
| `template/` | Minimal buildable app + `build.sh`, the shared builder |
| `scope/` | Scoping, scaffolding and the CI checks |
| `magisk-module/` | Overlay module packer — kept from the old plan, not the shipping path |
| `docs/` | Scope report, approach notes, adb/USB findings |
| `apks/`, `decompiled/` | Git-ignored; your own pulled OEM APKs and apktool output |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Short version: one app per PR, run
`scope/check-theme-wiring.sh`, never commit vendor APKs or decompiled vendor
sources, and say which unit you have.

Security issues: [SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE). Third-party components are listed in [NOTICE](NOTICE).
