# rav4-apps — clean-room rewrites of the GT6 head unit's built-in apps

Toyota RAV4 head unit: **GT6-CAR**, Qualcomm QCM6125, Android 13, UFS A/B.
Root via EDL -> TWRP -> Magisk (see ~/.claude memory `rav4-headunit-root-procedure`).

Goal: replace the buggy, typo-ridden OEM apps with our own, installed as a
**Magisk systemless overlay** so the real /system partition is never modified
(instant revert, survives A/B, no dm-verity fight).

## Layout
- `scope/scope-apps.sh` — pull + decompile + classify every OEM app (run with car online)
- `apks/`         — pulled OEM APKs (git-ignored, they're big/proprietary)
- `decompiled/`   — apktool resource output per package (strings, manifest)
- `apps/`         — our clean-room replacement apps (one Android project each)
- `magisk-module/`— template module that overlays our APKs onto the system app slots
- `docs/`         — scope-report.md and approach notes

## Workflow
1. Car on, adb up (USB or `adb connect <tailnet-ip>:5555`).
2. `./scope/scope-apps.sh [ip]`  -> produces `docs/scope-report.md`.
3. Read the report: **EASY** apps are pure UI (rewrite freely); **HW** apps talk
   to the vehicle via `android.car`/VHAL and need the vendor interface mapped first.
4. Pick a target, `jadx -d decompiled-full/<pkg> apks/<pkg>.apk` for full sources.
5. Build a replacement in `apps/<name>/`, drop the APK into the Magisk module,
   push + reboot, verify.

## Status
Toolchain staged (jadx 1.5.6, apktool 3.0.3, adb, Java 26). Waiting on the car to
be online for the first scope pass — everything else is ready.
