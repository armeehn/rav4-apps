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

## Where things run
All compute lives on **server x** (`x.hq.ripostelabs.xyz` = `pve-hq` on the
tailnet, 20 cores / 31G RAM): scoping, decompiling, building, module packing.
The primary working copy is `x:~/rav4-apps`; the laptop clone only pulls from it
(`git pull x`) and deploys to the unit with `./deploy-module.sh`. x is on the
tailnet, so it can adb-connect to the car directly when the car is online.

## Build environment (identical on x and laptop; no root, no Gradle)
- SDK `~/Android/Sdk`: platform **android-33** (= the car's API level), build-tools 34.0.0
- jadx 1.5.6 + apktool 3.0.3 in `~/.local/opt` (on PATH), adb, JDK (Temurin 21 on x)
- Gradle-free build: `aapt2 -> javac (-target 17) -> d8 -> zipalign -> apksigner`
  (proven: template + a scaffolded app both compile to signed APKs)
- Shared signing key `debug.keystore` (same key on both machines)

## Layout
- `scope/scope-apps.sh`        — pull + decompile + classify every OEM app (car online)
- `scope/generate-skeletons.sh`— scaffold a rewrite project per OEM app from the report
- `template/`     — minimal buildable Java app + `build.sh` (the shared builder)
- `apks/`         — pulled OEM APKs (git-ignored)
- `decompiled/`   — apktool resource output per package (strings, manifest)
- `apps/<pkg>/`   — our clean-room replacement per OEM package (same package name)
- `magisk-module/`— overlay module + `pack-app.sh` (drops APKs at OEM paths)
- `docs/`         — scope-report.md, approach.md

## Full pipeline (rewrite them all) — steps 1–5 on server x
1. Car on, adb up (`adb connect <car-tailnet-ip>:5555` from x; USB only via laptop).
2. `./scope/scope-apps.sh [ip]`         -> `docs/scope-report.md`
   (EASY / HW / DO-NOT-REPLACE / UNKNOWN).
3. `./scope/generate-skeletons.sh`      -> `apps/<pkg>/` for every eligible OEM app
   (**denylisted packages are refused**), each seeded with the OEM's own extracted
   strings so fixing typos is a direct diff.
4. Rewrite an app in `apps/<pkg>/` (start with EASY-classified; HW apps talk to the
   **szchoiceway gateway** and must have that interface reverse-engineered first —
   see docs/approach.md).
5. `apps/<pkg>/build.sh`                 -> signed `app-debug.apk`, then
   `magisk-module/pack-app.sh <pkg> apps/<pkg>/app-debug.apk` and
   `magisk-module/build-module.sh`       -> `rav4apps-module.zip`.
6. **Laptop:** `./deploy-module.sh [car-ip]` — fetches the built zip from x,
   pushes + installs it via Magisk (`--reboot` to activate immediately).

## Status
Toolchain + build pipeline fully validated offline (template and a test package
both build to signed APKs); toolchain mirrored onto server x 2026-08-27.
**Blocked only on the car being online** for the first scope pass — run it from x
the moment the unit connects.
