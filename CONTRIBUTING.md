# Contributing

These are clean-room rewrites of the built-in apps on one aftermarket head unit.
Help from people with the same hardware is the most useful kind.

## Before you open a PR

**Say which unit you have.** Findings rarely carry across variants:

```bash
adb shell getprop | grep -E "ro.product.(model|device)|persist.sys.mcu"
```

Developed against a **GT6-EAU** (MCU `RLC0_GT6E`, Qualcomm QCM6125, Android 13,
1920x720 @240dpi). A GT6-SE (`AT01_GT6SE`) is a different unit.

## Ground rules

- **One app per pull request.**
- **Clean-room only.** Do not paste decompiled vendor code into a rewrite. Using
  the OEM's extracted *strings* so a typo fix is a direct diff is fine and is what
  the scaffolder seeds; copying its logic is not.
- **Never commit vendor material** — OEM APKs, decompiled sources, firmware. The
  `apks/` and `decompiled/` directories are git-ignored working dirs.
- **Never commit device secrets** — IPs, serials, VINs, pairing codes, keystores.
- **Do not overlay a protected package.** `scope/protected-apps.sh` is the list;
  it exists because stubbing a gateway-reflected package takes the reverse camera
  and steering-wheel controls with it.

## Three checks that a build will not catch

Run `scope/check-theme-wiring.sh` before you push. It enforces all three, because
each one fails *silently* on the panel:

1. **`<queries>` for the theme provider.** Without it the app cannot see the
   launcher's palette and quietly keeps its own colours — indistinguishable from
   "no launcher installed".
2. **`Palette.apply(this)` in every Activity.** XML-set colours resolve at inflate
   time and will not follow a runtime palette; skipping this gives a half-themed
   screen.
3. **`MediaCitizen` for anything touching audio.** Without it the app plays over
   the radio, will not duck, is invisible to the launcher's now-playing card, and
   the steering-wheel media keys do nothing.

## Building

```bash
export ANDROID_SDK_ROOT=~/Android/Sdk    # platform android-33, build-tools 34.0.0
template/build.sh apps/com.ripostelabs.<name>
```

Needs a **real JDK 17** — `d8` from build-tools 34 crashes on newer class files
even with `--release 17`.

## Adding an app

`scope/generate-skeletons.sh` scaffolds from the scope report and refuses
denylisted packages. Start from a package classified EASY (self-contained UI);
anything talking to the vendor gateway needs that interface reverse-engineered
first — see `docs/approach.md` and the `CAR_API.md` in
[device-reveng](https://github.com/armeehn/device-reveng).
