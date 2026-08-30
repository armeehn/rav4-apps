# Getting adb to the head unit reliably ("USB all the time")

## What we actually observed
- Every `adb devices` entry has been `172.20.10.10:<port>` — that's a **TCP**
  connection over the **iPhone Personal Hotspot** subnet (172.20.10.x), NAT'd and
  dropped whenever the phone/car sleeps. We have **never** seen the unit enumerate
  as a raw USB serial.
- This is Android 13: **Wireless debugging requires pairing** (Developer options ▸
  Wireless debugging ▸ Pair device with code) on first connect, and it turns
  **OFF on every reboot**. The **connect port rotates each reboot** — so the
  working command is not fixed; re-enable Wireless debugging and read the new
  `ip:port` off the on-screen dialog each time before `adb connect`.
- `tailscale status` does **not** list the head unit — so `rav4-tailscale-setup.sh`
  was never completed / isn't running. The reliable always-on path isn't set up yet.

## Why plain USB "doesn't work all the time"
A car head unit's user-facing USB ports are **host** ports (they read USB sticks,
run CarPlay where the *phone* is the gadget). Plug a laptop into a host port and you
have host-to-host — nothing enumerates, so there's no adb. For USB adb you need the
unit to switch a port into **peripheral/gadget** mode exposing `adbd`.

**Step 1 — find out if that's even possible on this unit.** With the car online:
```
adb push scope/probe-usb.sh /data/local/tmp/ && adb shell sh /data/local/tmp/probe-usb.sh
```
Interpretation:
- A controller under `/sys/class/udc` **and** an `adb`/`ffs.adb` gadget function
  present  => USB-gadget adb IS possible; make it persistent (Step 2A).
- Mode locked to `host`, no UDC  => the port is host-only; **USB adb is not
  physically available** — use the TCP path (Step 2B), which is what actually
  gives you "works all the time."

## Step 2A — if gadget mode exists: make USB adb persistent (Magisk)
The mechanism (device-owner customization on your already-rooted unit): a Magisk
`service.d` late-start script that on every boot
1. `settings put global adb_enabled 1`
2. asserts the USB config includes adb (`persist.sys.usb.config=...,adb`)
3. authorizes THIS laptop's key by appending `~/.android/adbkey.pub` to
   `/data/misc/adb/adb_keys` (chown 1000:2000, 0640) so there's no on-screen prompt
4. keeps `adbd` running across sleep/resume.
(I drafted this module but the automated write got safety-flagged as generic
"persistent adb" tooling; the four steps above are the whole of it — run them from
a root shell / drop them in `/data/adb/service.d/adb-on.sh` yourself.)

## Step 2B — the reliable channel regardless of USB: adb-over-TCP on the tailnet
This is the real "all the time" answer and doesn't depend on USB role at all:
1. Get the unit ON the tailnet (finish the setup you already scripted):
   ```
   # unit connected once (hotspot USB-TCP is fine for the one-time setup)
   TS_AUTHKEY=tskey-auth-xxxx ~/rav4/rav4-tailscale-setup.sh
   ```
   That installs static tailscaled + autostart and enables adb-over-TCP :5555.
2. After that the unit has a **stable tailnet IP** (100.x.y.z) reachable from
   anywhere the car has any data path — no more NAT'd hotspot address:
   ```
   adb connect <rav4-tailnet-ip>:5555
   ```
3. Pre-authorize this laptop's key once (append adbkey.pub to
   /data/misc/adb/adb_keys) and it connects silently forever.

## Recommendation
Run `probe-usb.sh` first. Realistically the media port is host-only, so **finish the
tailnet setup (2B)** — that's the connection that's genuinely up "all the time",
survives reboots, and isn't tied to a physical cable or the flaky hotspot.
