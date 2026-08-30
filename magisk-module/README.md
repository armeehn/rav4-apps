# rav4apps Magisk module

Systemless overlay. Each replacement APK is stored under this dir at the SAME
path the OEM app occupies on the device, ALWAYS under `system/` (e.g. a
`/product/app/Foo/Foo.apk` file lives here as `system/product/app/Foo/Foo.apk`).
On boot Magisk overlays this `system/` tree over the real filesystem, so our APK
takes the OEM slot without any write to /system. Remove the module -> original
is back.

## Contents (all required for a flashable zip)
- `module.prop`            — module id/version metadata.
- `META-INF/com/google/android/update-binary` + `updater-script` — the Magisk
  installer entry point; without them the zip is NOT flashable.
- `customize.sh`           — install hook (sets perms on the overlaid APKs).
- `system/...`             — the replacement APKs at their on-device paths.
- `pack-app.sh`, `README.md` — authoring helpers, excluded from the zip.

## Use
1. Build your replacement app -> get a signed release APK.
2. `./pack-app.sh <package> path/to/replacement.apk`
   (auto-resolves the on-device path from ../docs/packages-raw.txt; refuses
   denylisted apps and package/sharedUserId mismatches)
3. `(cd . && zip -r ../rav4apps-module.zip . -x 'pack-app.sh' -x 'README.md')`
   (keep `customize.sh` and `META-INF/*` IN the zip — they make it flashable)
4. Flash in Magisk Manager (or `magisk --install-module rav4apps-module.zip`), reboot.

## Notes
- Package name + signature: overlaying works even with a different signing key
  because we replace the file wholesale; but if other apps verify this one's
  signature you may need to keep the package name and add the OEM cert to your
  keystore, or use a shared-uid-safe approach. Check per app.
- Keep the OEM `AndroidManifest` package name identical so intents/launcher slots
  resolve unchanged.
