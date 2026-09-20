# Projection (`com.ripostelabs.projection`)

Clean-room replacement for the OEM Zlink projection app. Stage 1 is a **wired Android Auto
receiver**: the head-unit side of the protocol a phone speaks over USB. Stage 2 adds the
**wireless bootstrap**: the phone learns the Wi-Fi credentials over Bluetooth and the same
session runs over TCP.

Original Java throughout. The protocol is publicly reverse-engineered
([aasdk](https://github.com/f1xpl/aasdk), [openauto](https://github.com/f1xpl/openauto),
[headunit](https://github.com/mikereidis/headunit); for the wireless bootstrap
[WirelessAndroidAutoDongle](https://github.com/nisargjhaveri/WirelessAndroidAutoDongle) and
[aa-proxy-rs](https://github.com/aa-proxy/aa-proxy-rs)); those are GPL or MIT and were read for
the wire-format facts only, which are cited per message in the source. No code from them is here,
and no jar, Gradle, Kotlin or native code either. Protobuf is a 200-line hand-written codec.

## What stage 1 does

| Step | Where | Status |
|---|---|---|
| Find the phone, switch it to AOA accessory mode (`GET_PROTOCOL`, six `SEND_STRING`s, `START`) | `UsbLink`, `aa/Aoa` | written, **no phone attached yet** |
| Open the accessory bulk endpoints, reader thread | `UsbLink`, `Projector` | written, untested on hardware |
| Framing: channel, flags, length, fragmentation, per-frame TLS | `aa/Frame`, `aa/Session` | unit-tested with hand-built vectors |
| Version request 1.1, TLS 1.2 handshake (head unit = client), auth complete | `aa/Session`, `aa/Tls` | unit-tested against a server engine that demands the client cert |
| Service discovery: video, input, sensor (driving status, night), media/speech/system audio | `aa/Messages`, `aa/Session` | unit-tested (byte-exact where headunit has a literal) |
| Channel open, AV setup/start/stop, media ack, video focus, binding, sensor start | `aa/Session` | unit-tested with a scripted phone |
| H.264 to `MediaCodec` on a `SurfaceView` | `VideoSink`, `MainActivity` | written, untested |
| PCM to `AudioTrack` through `MediaCitizen` focus | `AudioSink`, `Projector` | written, untested |
| Touch to `InputEventIndication` | `MainActivity`, `aa/Messages` | encoding unit-tested, mapping untested |
| Ping, navigation focus, audio focus, shutdown | `aa/Session` | unit-tested |

Every protocol transition logs one line under the `Projection` tag and puts it on the status
strip, so the first real session says exactly where it stops:

```
adb logcat -s Projection
```

## What stage 2 does

| Step | Where | Status |
|---|---|---|
| RFCOMM server under `4de17a00-52cb-11e6-bdf4-0800200c9a66`, name "AA Wireless" | `BtRfcomm` | written, **no phone paired yet** |
| Framing on that link: `u16 length`, `u16 message id`, big-endian, then protobuf | `aa/Wifi` | unit-tested with hand-built vectors |
| `WifiVersionRequest` / `WifiVersionResponse` (ids 4 / 5) | `aa/Wifi` | encoding tested; the version number is a guess (below) |
| `WifiStartRequest` (1) with the AP's IP and port 5288 | `aa/Wifi` | byte-exact vector |
| `WifiInfoRequest` / `WifiInfoResponse` (2 / 3): ssid, key, bssid, security, AP type | `aa/Wifi` | byte-exact vector |
| `WifiStartResponse` (7), `WifiConnectStatus` (6), ping (8 / 9) | `aa/Wifi` | unit-tested, incl. the negative status a failing phone sends |
| Bootstrap state machine VERSION → INFO → START → CONNECTED | `aa/Wifi.Bootstrap` | unit-tested with a scripted phone, both with and without a version exchange |
| Soft-AP via `startLocalOnlyHotspot`, AP address by interface diff | `SoftAp` | written, untested (emulator has no Wi-Fi AP) |
| TCP server on 5288, `Session` over the accepted socket | `TcpTransport`, `Projector` | written, `Session` itself is stage 1's, unchanged |
| Wireless toggle, runtime permissions, one status line per state | `MainActivity`, `Wireless` | launch smoke on the emulator only |

### Ports: 5288, not 5277

The phone dials whatever `WifiStartRequest.port` says. Both wireless references put **5288**
there (the dongle's `AAWG_PROXY_PORT` default; AAWireless adapters use the same). mikereidis/headunit's
**5277** is a different thing: its head unit *connects out* to a companion server app on the
phone over an `adb forward`, a developer transport, and its wifi-direct mode listens on 30515 for
that same companion. Neither is what a stock phone does, so this uses 5288 and it is a constant
(`Wifi.DEFAULT_PORT`), not a setting.

### Where the field numbers come from

- `WifiStartRequest{ip_address=1, port=2}` and `WifiInfoResponse{ssid=1, key=2, bssid=3,
  security_mode=4, access_point_type=5}` with the `SecurityMode` (WPA2_PERSONAL=8) and
  `AccessPointType` (DYNAMIC=1) enums: the dongle's `proto/*.proto`, which are the two messages it
  sends.
- Message ids 1..9: the dongle's `bluetoothProfiles.cpp` enum and aa-proxy-rs `bluetooth.rs`
  agree.
- `WifiVersionRequest{major=1, minor=2, supported_wifi_channels=3, head_unit_info=4{car_make=1,
  car_model=2, car_year=3, vehicle_id=4, head_unit_make=5, head_unit_model=6, software_build=7,
  software_version=8}, projection_protocol_info=5{ip_address=1, port=2}}`,
  `WifiVersionResponse{major=1, minor=2, device_serial=3, status=4, selected_wifi_channel_type=5,
  device_info=6{device_id=1, connectivity_lifetime_id=2}}`, `WifiStartResponse{ip_address=1,
  port=2, status=3}`, `WifiConnectStatus{status=1}`, ping `{timestamp=1}`: aa-proxy-rs's debug
  decoder, which it wrote against decompiled Gearhead and MBUX captures. It notes a second
  `WifiVersionRequest` layout (head unit info at 5, a varint at 4); the Gearhead one is followed.
- The Zlink decompile (`decompiled/com.zjinnova.zlink-unpacked/dex0/`) has **no** phone-facing
  wifi messages. The only AP message there is the APK↔daemon `BtApInfoMsg{message_id=1,
  ap_ssid=2, ap_passwd=3, ap_band=4, ap_interface_name=5}` (`proto/BtApInfoProto$BtApInfoMsg.java`);
  the phone-facing encoder lives in the native daemon. It confirms the OEM handed the phone the
  unit's own hotspot SSID/passphrase and band as read, nothing more.

### The soft-AP and what needs Riposte OS 0.2

On Android 13 a normal app has exactly one way to raise an access point,
`WifiManager.startLocalOnlyHotspot`, and it comes with:

- a random SSID and passphrase per start. Harmless here: the phone gets both over Bluetooth.
- no band choice. The platform picks, 2.4 GHz on every device seen; the overload that takes a
  `SoftApConfiguration` (5 GHz) is `@SystemApi`, and even `getBand()` on the result is.
- a lifetime tied to the reservation and the process.
- `NEARBY_WIFI_DEVICES` at runtime (API 33; `ACCESS_FINE_LOCATION` before).
- no API for the AP's own address: `SoftAp` snapshots the IPv4 interfaces before the start and
  takes the one that appears.

The OEM app read the unit's hotspot settings and called `ConnectivityManager.startTethering`
(device-reveng `ZLINK_REWRITE.md` §8). That path is in `SoftAp.startTethering` behind
`SoftAp.USE_TETHERING = false`: reflective hidden API, needs `TETHER_PRIVILEGED`, so it only
works once this app is a priv-app on Riposte OS 0.2. Reading the tethered AP's settings back
(`getSoftApConfiguration`, also system) is not wired yet. Bluetooth pairing is left to
Settings or the suite's bluetooth app; `BluetoothAdapter` profile control needs
`BLUETOOTH_PRIVILEGED` too.

## What is not tested

**No phone has been paired or plugged in.** Stage 2 specifically:

1. The bootstrap protocol version. `Wifi.VERSION_MAJOR/MINOR` is 5.1, aa-proxy-rs's override
   default; no capture of what a phone accepts from a new head unit exists here.
2. Whether the phone answers `WifiVersionRequest` at all, or asks for credentials straight away
   as the dongle path assumes. `Bootstrap` accepts both.
3. Whether the phone needs a HFP/HSP link to the same adapter before it dials the RFCOMM
   service (both references establish one first; this app does not).
4. Whether the phone accepts an empty `bssid` when `SoftApConfiguration.getBssid()` is null.
5. Whether a random-SSID local-only hotspot on 2.4 GHz gives usable video.
6. The AP address heuristic (interface diff) on the real unit's Wi-Fi driver.

Stage 1's list still applies once the socket is up. On the emulator (API 33) the toggle gets
as far as it can: TCP listening, a fake local-only hotspot (`AndroidShare_xxxx`, WPA2), the
RFCOMM record registered. No phone can reach it there; that is the smoke test. The emulator farm has no USB host, so on it the app only
proves it launches and that Connect does not crash. Everything below the AOA switch is verified
against the references and against a fake phone in `test/`, not against Google's app. Things
that could still be wrong on the first real session, in the order they would show up:

1. Whether the phone accepts a `ClientHello` from Android's TLS stack as readily as OpenSSL's
   (protocol pinned to TLSv1.2, ciphers left to the platform).
2. Whether the phone honours the 360-row bottom margin on the 1080p config. The panel is
   1920x720 and Android Auto offers 800x480, 1280x720 and 1920x1080; declaring 1080p with a
   margin is the only way to get a 1920-wide picture. 720p is offered second as the fallback.
3. The declared DPI (160) and the touch coordinate space (the first config's 1920x1080).
4. In-band SPS/PPS through `MediaCodec` without a codec-config buffer.
5. Ping direction and encryption: aasdk sends its own ping request plain after the handshake
   and this does the same; the phone's ping requests are answered encrypted.

The AOA identity strings match aasdk exactly except the URI, which is ours; headunit sends only
manufacturer and model, so the URI is not what the phone matches on.

## Build and test

```bash
template/build.sh apps/com.ripostelabs.projection     # APK
scope/run-tests.sh                                     # 7 test classes under test/
scope/check-theme-wiring.sh
```

`test/` covers only `src/.../aa/`, which is pure Java by design; `SessionTest` drives the whole
state machine with a scripted phone and a real server-side `SSLEngine`; `WifiTest` drives the
bootstrap the same way.

## Layout

```
src/com/ripostelabs/projection/
  aa/Aoa.java        accessory-mode switch: request codes, string table, transfer sequence
  aa/Frame.java      transport framing, fragmentation, reassembly, stream reader
  aa/Proto.java      protobuf varint / fixed / length-delimited writer and reader
  aa/Ids.java        channel numbers and message ids
  aa/Messages.java   the messages, field numbers cited from aasdk_proto/*.proto
  aa/Tls.java        SSLEngine client with the well-known head-unit certificate and key
  aa/Session.java    the state machine: version, TLS, discovery, channels, media, touch
  aa/Wifi.java       wireless bootstrap: RFCOMM framing, wifi messages, Bootstrap state machine
  UsbLink.java       UsbManager: find, switch, open, bulk read/write
  TcpTransport.java  the same Pipe over a TCP socket the phone opens
  BtRfcomm.java      RFCOMM server under the AA wireless UUID
  SoftAp.java        local-only hotspot (tethering path behind a flag)
  Wireless.java      stage 2 end to end: AP, TCP listen, RFCOMM accept, bootstrap, hand-off
  Projector.java     reader thread, ping timer, decoder, audio tracks, MediaCitizen
  VideoSink.java     MediaCodec H.264 decoder onto the surface
  AudioSink.java     AudioTrack per audio channel
  MainActivity.java  surface, status line, USB intents, touch mapping
```

## The certificate

`aa/Tls.java` embeds the certificate and RSA key every open receiver ships: a "JVC Kenwood" leaf
issued by "Google Automotive Link", valid 2014 to 2045. It is a public test pair, not a secret
and not ours. The phone asks the head unit for a client certificate during the TLS handshake and
this is the one it has accepted from those projects for years. Neither reference verifies the
phone's certificate and neither carries a CA for it; this does the same.

## Roadmap

- **Stage 2, wireless Android Auto**: written (above); needs a phone, then the priv-app AP
  path on Riposte OS 0.2 for a chosen SSID and 5 GHz.
- **Stage 3, CarPlay**: the unit has an MFi authentication chip. That path depends on
  `ZLINK_REWRITE.md` in device-reveng recovering how the OEM app reaches it.
- Also missing: microphone (AV input channel), steering-wheel keys as button
  events, Bluetooth channel for hands-free pairing, night mode from the car.

## CarPlay on Riposte OS 0.2 (through the OEM daemon)

The OEM projection daemon (`zlink5`, a root init service on the stock kernel) does the whole
CarPlay job: iAP2, the MFi authentication chip on i2c, AirPlay. Its own app cannot run on the
GSI base (vendor platform key), and it needs an app for six things. This package is that app,
in `src/.../zlink/` plus `ZlinkService`, `CarPlayActivity` and `CarPlayWireless`:

```
 daemon ──1777──▶ control   session state, InitInfo, MFi, touch, keys, AP request   (protobuf)
 daemon ──1888──▶ video     0x302: 20-byte header + Annex-B H.264 ──▶ MediaCodec on a SurfaceView
 daemon ──1666──▶ audio     0x202: 24-byte header + PCM ──▶ AudioTrack behind MediaCitizen
 daemon ◀─1999──▶ bluetooth the iPhone's iAP2 RFCOMM bytes, relayed raw both ways
 frame = ff ff ff 10 | u32 length | u32 id | payload      (all big-endian)
```

Everything here was learned on the bench by black-box capture and an id sweep read back from
the daemon's own log names (device-reveng RAV4-92 has the record); no OEM code was read.

| Step | Status (bench, 2026-09-20) |
|---|---|
| Daemon bootstrap: InitInfo, MFi info (chip genuine), session states | works |
| Wireless: bonded iPhone → iAP2 over RFCOMM relay → identification + MFi auth | works |
| Access point: the OS's 5 GHz AP (`riposte.ap.*` props), app hotspot as fallback | works with the OS AP; the fallback is 2.4 GHz, which the phone refuses |
| Video: 1920x720 H.264 to the panel | works, 397 of 400 units rendered |
| Audio: 44.1 kHz stereo PCM | frames decoded, playback unverified (no speaker on the bench) |
| Touch: panel pixels straight to the daemon | works |
| Wired (USB, iAP2 gadget + NCM) | not tried yet: needs the port in host mode |
| Siri, calls, mic | not started |
