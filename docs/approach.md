# Approach & constraints

## Two rewrite modes
- **Clean-room rewrite (default):** new Android app, our UI + correct copy.
  Best for self-contained apps (settings screens, media/USB player, clock,
  notes, BT phone UI). NOT the launcher: the stock `com.szchoiceway.customerui`
  is not an Android HOME app — its home UI is a *window inflated by name from
  the gateway process* (CUSTOMERUI_NOTES.md §2), so it cannot be overlay-swapped.
  A replacement home screen is a separate side-loaded HOME app, not part of this
  rewrite set (customerui is on the denylist).
- **Decompile-and-patch (fallback):** `apktool d` -> fix strings/small logic ->
  `apktool b` -> re-sign. For apps too deeply wired into vendor services to
  cleanly reimplement.

## The three real constraints (in bite order)
1. **Install/replace.** Sideloading a *new* app is trivial with adb. *Replacing*
   a system app needs root -> use a **Magisk systemless module** overlaying the
   APK; never touch /system directly (A/B + dm-verity safe). Revert = disable the
   module (Magisk safe mode / adb) and reboot; worst case restore the EDL backup.
2. **Vehicle boundary.** This unit has **no AOSP car framework** — there is no
   `android.car` / `CarPropertyManager` / VHAL. Anything touching
   climate/radio/camera/reverse/CAN goes through the **szchoiceway gateway**
   (`com.szchoiceway.eventcenter`, runs as `android.uid.system`): its
   `EventService` AIDL, the `SysVarProvider` content provider (car settings),
   the MCU serial link `/dev/ttyHS1`, and protected broadcasts guarded by
   `com.szchoiceway.permission.broadcast`. Rewriting these = reverse those
   interfaces first (see CAR_API.md). This is the expensive part; the scope
   script flags them **HW**.
3. **Signing/permissions.** `signature`-level perms are granted only to apps
   signed with the **platform key**, which is **confirmed unobtainable**
   (CUSTOM_ANDROID.md §2b) — so a platform-signed build is ruled out. Placing an
   app in `/system/priv-app` only whitelists it for *privileged* perms; it does
   **not** make it platform-signed and does **not** grant `signature` perms, and
   it cannot join `sharedUserId="android.uid.system"`. Privileged/gateway flows
   therefore degrade; a root `app_process` helper (uid 0) is the fallback for the
   `signature`-protected broadcasts, not a system-app install.

## Easy vs hard
- Easy & low-risk: cosmetic/utility/self-contained apps.
- Hard: gateway-facing apps — RE the szchoiceway interface first, app-writing second.
- Off-limits: denylisted safety-critical / reflected apps (`scope/protected-apps.sh`).
