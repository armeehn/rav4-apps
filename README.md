# rav4-apps — clean-room rewrites of the GT6 head unit's built-in apps

Toyota RAV4 head unit: **GT6-EAU** (Android product id GT6-CAR), Qualcomm
QCM6125, Android 13 (API 33), UFS A/B.
Root via EDL -> TWRP -> Magisk (see ~/.claude memory `rav4-headunit-root-procedure`).

Goal: replace the buggy, typo-ridden OEM apps that are **self-contained UI**
(the scope script's EASY class) with clean-room rewrites, installed as a
**Magisk systemless overlay** so the real /system partition is never modified
(survives A/B, no dm-verity fight). This is NOT "replace everything": a denylist
of safety-critical / gateway-reflected apps (`scope/protected-apps.sh` —
eventcenter, customerui, canbus/canbus2, auxcamera, radio, dsp) is refused by
the tooling. Reverting the overlay is not literally instant but is low-risk:
disable the module (Magisk safe mode, or `magisk --remove-modules` over adb)
and reboot; worst case, restore from the EDL backup.

## Build environment (all present, no root, no Gradle)
- SDK `~/Android/Sdk`: platform **android-33** (= the car's API level), build-tools 34.0.0
- jadx 1.5.6 + apktool 3.0.3 in `~/.local/opt` (on PATH), adb, Java 26 (JDK)
- Gradle-free build: `aapt2 -> javac (-target 17) -> d8 -> zipalign -> apksigner`
  (proven: template + a scaffolded app both compile to signed APKs)

## Layout
- `scope/scope-apps.sh`        — pull + decompile + classify every OEM app (car online)
- `scope/generate-skeletons.sh`— scaffold a rewrite project per OEM app from the report
- `template/`     — minimal buildable Java app + `build.sh` (the shared builder)
- `apks/`         — pulled OEM APKs (git-ignored)
- `decompiled/`   — apktool resource output per package (strings, manifest)
- `apps/<pkg>/`   — our clean-room replacement per OEM package (same package name)
- `magisk-module/`— overlay module + `pack-app.sh` (drops APKs at OEM paths)
- `docs/`         — scope-report.md, approach.md

## Pipeline (rewrite the EASY apps)
1. Car on, adb up (USB or `adb connect <ip>:5555`).
2. `./scope/scope-apps.sh [ip]`         -> `docs/scope-report.md` (EASY / HW /
   DO-NOT-REPLACE / UNKNOWN).
3. `./scope/generate-skeletons.sh`      -> `apps/<pkg>/` for each eligible OEM
   app (denylisted packages are refused), seeded with the OEM's own extracted
   strings so fixing typos is a direct diff.
4. Rewrite an app in `apps/<pkg>/` (start with EASY-classified; HW apps talk to
   the szchoiceway gateway and must have that interface reverse-engineered
   first — see docs/approach.md).
5. `apps/<pkg>/build.sh`                 -> signed `app-debug.apk`.
6. `magisk-module/pack-app.sh <pkg> apps/<pkg>/app-debug.apk` then zip + flash the
   module in Magisk, reboot, verify.

## Status
Toolchain + build pipeline fully validated offline (template and a test package
both build to signed APKs). **Blocked only on the car being online** for the first
scope pass — as of 2026-08-27 the unit was powered off / off adb. Everything else
is ready to run the moment it connects.
