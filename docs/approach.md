# Approach & constraints

## Two rewrite modes
- **Clean-room rewrite (default):** new Android app, our UI + correct copy.
  Best for self-contained apps (settings screens, media/USB player, clock,
  notes, launcher, BT phone UI).
- **Decompile-and-patch (fallback):** `apktool d` -> fix strings/small logic ->
  `apktool b` -> re-sign. For apps too deeply wired into vendor services to
  cleanly reimplement.

## The three real constraints (in bite order)
1. **Install/replace.** Sideloading a *new* app is trivial with adb. *Replacing*
   a system app needs root -> use a **Magisk systemless module** overlaying the
   APK; never touch /system directly (A/B + dm-verity safe, instant revert).
2. **Vehicle boundary.** Apps touching climate/radio/camera/EV/CAN go through the
   **VHAL** (`android.car`, CarPropertyManager, vehicle property IDs) and/or
   proprietary Toyota/Denso AIDL/HIDL services. Rewriting these = reverse the
   property IDs + service interfaces first. This is the expensive part; the
   scope script flags these as **HW**.
3. **Signing/permissions.** `signatureOrSystem` perms are granted only to apps
   signed with the platform key (we don't have it). A Magisk-module app placed in
   /system can still hold them; a plain user-sideloaded app cannot. Another reason
   to route privileged apps through the module.

## Easy vs hard
- Easy & low-risk: cosmetic/utility/self-contained apps.
- Hard: hardware-facing apps — RE effort first, app-writing second.
