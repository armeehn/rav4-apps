# rav4apps Magisk module

Systemless overlay. Each replacement APK is stored under this dir at the SAME
path the OEM app occupies on the device (e.g. `system/product/app/Foo/Foo.apk`).
On boot Magisk overlays this tree over the real filesystem, so our APK takes the
OEM slot without any write to /system. Remove the module -> original is back.

## Use
1. Build your replacement app -> get a signed release APK.
2. `./pack-app.sh <package> path/to/replacement.apk`
   (auto-resolves the on-device path from ../docs/packages-raw.txt)
3. `(cd . && zip -r ../rav4apps-module.zip . -x '*.sh' -x 'README.md')`
4. Flash in Magisk Manager (or `magisk --install-module rav4apps-module.zip`), reboot.

## Notes
- Package name + signature: overlaying works even with a different signing key
  because we replace the file wholesale; but if other apps verify this one's
  signature you may need to keep the package name and add the OEM cert to your
  keystore, or use a shared-uid-safe approach. Check per app.
- Keep the OEM `AndroidManifest` package name identical so intents/launcher slots
  resolve unchanged.
