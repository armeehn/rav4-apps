# GT6 head unit — vendor/OEM app inventory & removal plan

_Device: `GT6-CAR` (Qualcomm QCM6125, Android 13), reached via adb over the
tailnet (the LAN address was unreachable this session).
Snapshot taken 2026-08-30. 259 packages installed total._

Goal: reverse, document, and re-implement every **vendor/OEM app** (Choiceway /
ZJinnova / aftermarket integrator) with our own `com.reveng.*` apps, then
**remove** the OEM originals — leaving a clean, private, themed stack.

This doc is the master map + sequenced plan. It supersedes the crude
`scope-report.md` (which mis-classified the whole system image, flagging AOSP
baseline like Chrome / IMS / simcontacts as "vehicle-facing").

---

## 1. The three hard constraints (read first)

1. **Every OEM app runs as `android.uid.system` (UID 1000) and lives on the
   read-only `/product` or `/system/priv-app` partition.** Confirmed for all 30+
   Choiceway/ZJinnova packages. Consequences:
   - You **cannot `pm uninstall`** a system-partition app for the real user;
     you can only `pm disable-user` / `pm uninstall --user 0` (hide it) or drop a
     Magisk systemless module that removes/overlays it. Removal ≠ uninstall;
     removal = **disable, systemlessly**. This is A/B + dm-verity safe and
     instantly revertible.
   - Our replacements are **plain user apps** (`com.reveng.*`, signed with our
     debug/release key, installed to `/data`). They do **not** get system UID and
     do **not** hold `signatureOrSystem` perms. Anything that truly needs system
     UID (writing vendor SysVar keys, claiming tuner audio) must go through a
     privileged shim, not the user app — see §3.
2. **Platform key is unobtainable** (confirmed — not in ROM, not AOSP test-key).
   So we can never *re-sign as the OEM*. Replacement strategy is therefore
   "new app alongside + disable original," never "patch original in place."
3. **A shared service backbone binds the whole OEM app set** (§3). Several apps
   are pure vehicle plumbing (CAN, settings store, event bus). Those **must not
   be removed** — they get *preserved* (kept as-is) or *reimplemented behind the
   same contract*, never deleted. Deleting `SysVarProvider` or `EventCenter`
   bricks the top-bar / SWC / HVAC (we already learned the JSON-key-crash lesson
   on `EventService`).

---

## 2. Current state — replacements exist, cutover has NOT happened

The `com.reveng.*` fleet is already **installed** on the unit (29 apps) and
`com.reveng.carlauncher` is the live HOME. **But almost nothing has been cut
over.** Only two OEM apps are disabled:

- `com.mmbox.xbrowser` → **DISABLED** (replaced by `com.reveng.browser`)
- `com.ivicar.avm` (360/AVM camera) → **DISABLED** (feature dropped, no replacement)

Every other OEM app is **still enabled and still the effective intent handler**
— e.g. an `audio/mpeg` VIEW intent still resolves to `com.szchoiceway.musicplayer`,
not `com.reveng.music`. So the bulk of remaining work is not *writing* apps
(most exist) — it is the **cutover + disable** step, done safely and in order.

---

## 3. The shared backbone — what MUST stay (or be reimplemented behind a contract)

These are not "apps to remove." They are the vehicle-integration substrate every
other app (ours included) depends on. Reverse-engineering them is largely **done
and captured in memory**; the plan is to *keep* them until we ship a
contract-compatible reimplementation.

| Package | Role | Contract status | Action |
|---|---|---|---|
| `com.szchoiceway.providers.settings` (`SysVarProvider`, `/system/priv-app`) | **Settings + vehicle-state store.** Authority `com.szchoiceway.eventcenter.SysVarProvider`. Every setting/toggle reads/writes here. | Contract known (launcher already reads/writes it); JSON-typed-key crash hazard documented. | **KEEP.** All Reveng apps talk to it. Do not disable. |
| `com.szchoiceway.eventcenter` (`EventCenter`, "canbus.TestActivity") | **CAN event bus + audio-claim + tuner backend** (the `EventService` binder). Drives top-bar, SWC, HVAC, radio audio routing. | Binder contract reversed (memory: tuner interface, EventService). | **KEEP.** Reimplement only behind the same binder if ever. |
| `com.szchoiceway.canbus2` | Core CAN service (HiWorld TYF2 CANBOX digest decode). | Serial digest decoded (memory: CANBOX serial decode). | **KEEP.** |
| `com.szchoiceway.canoriginalcarmedia` | OEM "original car media" source bridge (原车媒体) — routes factory media/aux audio. | Not fully reversed. | KEEP for now; candidate for later fold-in to Reveng media routing. |
| `com.core.ex.provider.CoreContentProvider` (in-app class, shared by settings/customerui/atslconsole/ambient/gps/learn.key) | OEM common key-value data layer used across their apps. | Partially known. | Not a standalone app — dies when its host apps are disabled; verify no Reveng app relies on it. |
| `com.lfg.szchoiceway.canupgrade` | CAN/MCU firmware updater. | n/a | **KEEP** (needed for MCU flashing). |

**Rule:** a guessed vendor contract that drives a real actuator (amp, camera,
CAN write) must be gated/verified before shipping live — see the balance/fader
miss (memory: audit-gate-guessed-contracts).

---

## 4. Full vendor/OEM inventory (removal targets), by tier

Labels are the on-device `application-label`. "Replacement" = our app;
"Cutover" = has the OEM original been disabled yet.

### Tier A — user-facing apps: replacement exists, just needs cutover
| OEM package | Label | Reveng replacement | Cutover |
|---|---|---|---|
| `com.mmbox.xbrowser` | X Browser | `com.reveng.browser` | ✅ done |
| `com.szchoiceway.musicplayer` | Music | `com.reveng.music` | ❌ pending |
| `com.szchoiceway.zxwmedia` | (media backend) | `com.reveng.music`/`video` | ❌ pending |
| `com.szchoiceway.videoplayer` | Video | `com.reveng.video` (or `is.xyz.mpv`) | ❌ pending |
| `com.szchoiceway.radio` | Radio | `com.reveng.radio` (tuner contract done) | ❌ pending |
| `com.choiceway.weather` | Weather | `com.reveng.weather` | ❌ pending |
| `com.szchoiceway.photoreader` | Photos | `com.reveng.photos` | ❌ pending |
| `com.szchoiceway.gps` | GPS | `com.reveng.gps` | ❌ pending |
| `com.szchoiceway.btsuite` | BT phone/suite | `com.reveng.bluetooth` | ❌ pending |
| `com.szchoiceway.customerui` | CustomerUI (OEM home/personalization) | `com.reveng.carlauncher` | ⚠️ launcher is HOME, OEM still enabled |
| `com.szchoiceway.navigation` | Navigation | Google Maps + `com.reveng.gps` | ❌ pending |
| `com.szchoiceway.apkinstall` | APK installer | `com.reveng.installer` | ❌ pending |
| `com.szchoiceway.instructions` | Manual/Instructions | (low priority Reveng doc app, or drop) | ❌ pending |

### Tier B — hardware-feature apps: reverse the HW interface, then replace
| OEM package | Label | Hardware touchpoint | Replacement status |
|---|---|---|---|
| `com.android.atslcarconsole` | **Console** (plate-setting, CAMERA) | vehicle info/console, plate, camera | none yet — candidate `com.reveng.deviceinfo`/new console |
| `com.choiceway.dsp` | **DSP** | audio DSP/EQ (amp path — gated!) | none yet — fold into `com.reveng.music` or new `reveng.dsp` |
| `com.szchoiceway.auxcamera` | **AUX** (`navigation.AUXActivity`, CAMERA) | aux/reverse camera video-in | none yet — **safety-relevant, do last & verify** |
| `com.ivicar.avm` | AVM 360 | around-view cameras | ✅ disabled (feature dropped) |
| `com.szchoiceway.ambient.light` | Bluetooth AmbientLight | interior RGB over BT/protocol | 3rd-party `wl.smartled.rgb` present; no Reveng app |
| `com.szchoiceway.multicolor.light` | Multicolor light | interior RGB | none yet |
| `com.szchoiceway.gesture` | GesturePlay | proximity/gesture sensor (`persist.choiceway.gesture.enabled=0` → already off) | none needed — disable |
| `com.szchoiceway.zxw_dashboard` | Dashboard / Gyro | driving stats + gyroscope | partial: `com.reveng.speedometer`/`level`/`compass` |
| `com.szchoiceway.learn.key` | SWC key learn | steering-wheel-control key mapping | launcher handles SWC; verify before disable |
| `com.zjinnova.zlink` | ZLink (CarPlay/AA/mirror) | proprietary phone-projection stack (bundles **Tencent MID** telemetry) | **not cleanly replaceable** — keep, but gate features via `rw.zlink.disable.features`; strip/kill Tencent phone-home |

### Tier C — factory / diagnostic / telemetry: disable, no replacement
| OEM package | Label | Action |
|---|---|---|
| `com.szchoiceway.testtools` | 测试工具 (factory test) | disable (keep an APK stashed for diagnostics) |
| `com.szchoiceway.canbusdebug` | CAN debug | disable |
| `com.szchoiceway.logcatupload` | logcat phone-home | **disable** (privacy) |
| `com.szchoiceway.update` | OEM OTA | **disable** (privacy / prevent surprise OEM updates) |

---

## 5. Rewrite/removal mechanics

**Cutover of a Tier-A app (the repeatable recipe):**
1. Confirm the Reveng replacement handles every intent the OEM app did
   (`cmd package query-activities` for MAIN/VIEW/SEND + the OEM's custom
   `com.szchoiceway.*`/`com.choiceway.*` broadcast actions).
2. Back up any user data the OEM app owns **before** touching it (uninstall wipes
   DataStore irrecoverably — learned the hard way). For OEM apps that's mostly
   SysVar keys (safe, they persist in `SysVarProvider`).
3. Make Reveng the default handler (`cmd package set-home-activity` already done
   for launcher; for others, set preferred activity or just rely on the OEM being
   disabled so ours is the sole resolver).
4. **Disable, don't uninstall:** `pm disable-user --user 0 <pkg>` (reversible in
   one command). Only after a soak period, land a Magisk module that removes the
   `/product/app/<X>` dir for a truly clean image.
5. Reboot, verify top-bar/SWC/HVAC/radio still work (the backbone smoke test),
   and note the change in the device state log.

**Never** disable a §3 backbone package. **Never** side-load a Reveng *launcher*
feature branch as the home app (reverts the unit — see coordination rules); only
`main` goes on-device as the launcher.

**Rollback:** everything here is `pm enable <pkg>` or `magisk module disable` +
reboot. No partition writes.

---

## 6. Phased execution plan

**Phase 0 — safety net (once).**
Snapshot `pm list packages -f`, dump all SysVar keys, `LauncherBackup`, stash
every OEM APK we might want back (`adb pull /product/app/...`). Land a Magisk
`service.d` guard that re-enables backbone packages if a boot loops.

**Phase 1 — privacy quick wins (Tier C, zero replacement needed).**
Disable `logcatupload`, `update`, `canbusdebug`, `testtools`, `gesture`. Strip
Tencent MID from the ZLink runtime (block its host / no-op the provider). Fast,
high-value, low-risk.

**Phase 2 — Tier-A cutover wave (replacements already built).**
For each of music/zxwmedia, video, radio, weather, photos, gps, btsuite,
navigation, apkinstall, instructions: run the §5 recipe, one app per soak cycle,
backbone smoke test between each. This is the bulk of the "removal" and is mostly
config, not code. Retire `customerui` last in this wave (confirm no Settings deep
-link still routes into it).

**Phase 3 — Tier-B hardware features (RE first, then build).**
Priority order by risk/value:
1. `dsp` → build `reveng.dsp` (or fold EQ into `reveng.music`); verify amp path is
   gated before shipping (audit rule).
2. `zxw_dashboard` → finish `reveng.speedometer`/dashboard parity, then disable.
3. `ambient.light` + `multicolor.light` → one `reveng.ambient` over the same BT
   protocol; disable both OEM apps.
4. `atslcarconsole` (Console) → decide keep-vs-replace; it holds plate-setting +
   camera console. Likely a new `reveng.console`.
5. `auxcamera` → **last, and verified live** (reverse-camera is safety-critical;
   a wrong camera contract = no backup view). Keep OEM until Reveng aux is
   proven on-device.
6. `learn.key` → confirm launcher fully owns SWC mapping, then disable.

**Phase 4 — ZLink (keep, harden).**
Not replaced. Keep CarPlay/AA/mirror; continue gating unwanted modes via
`rw.zlink.disable.features`; neutralize Tencent telemetry. Revisit only if a
clean-room projection stack becomes worth it.

**Phase 5 — backbone (long horizon, optional).**
Only if we ever want a 100%-Reveng image: reimplement `SysVarProvider` +
`EventCenter` + `canbus2` behind their exact contracts, swap via Magisk, keep the
OEM APKs as instant rollback. High effort, low near-term value — deferred.

---

## 7. Open reverse-engineering items (blockers for specific apps)
- `com.core.ex.provider.CoreContentProvider` full key schema (shared data layer) —
  confirm nothing we ship depends on it before disabling its host apps.
- `canoriginalcarmedia` audio-routing contract (needed before Reveng owns the
  "original car media"/aux source).
- `atslcarconsole` vehicle-info + plate + camera binder calls.
- `auxcamera` / reverse-camera video-in contract (safety-gated).
- `dsp` amp/EQ write path (must be gated like radar/balance).

---

## Appendix — raw evidence
Collected this session into the job tmp: `launchable.txt`, `pkgs-all.txt`,
`pkgs-disabled.txt`, `vendor-meta.txt` (shared-UID + providers), `providers.txt`,
pulled+badged APKs under `apks/`. Vendor system-prop surface (`rw.zlink.*`,
`persist.zxw.*`, `Sys.Zxw.*`, `persist.choiceway.gesture.enabled`) captured for
the settings map.
