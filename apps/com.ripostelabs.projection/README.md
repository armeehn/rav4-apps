# Projection (`com.ripostelabs.projection`)

Clean-room replacement for the OEM Zlink projection app. Stage 1 is a **wired Android Auto
receiver**: the head-unit side of the protocol a phone speaks over USB.

Original Java throughout. The protocol is publicly reverse-engineered
([aasdk](https://github.com/f1xpl/aasdk), [openauto](https://github.com/f1xpl/openauto),
[headunit](https://github.com/mikereidis/headunit)); those are GPL and were read for the
wire-format facts only, which are cited per message in the source. No code from them is here,
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

## What is not tested

**No phone has been plugged in.** The emulator farm has no USB host, so on it the app only
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
scope/run-tests.sh                                     # 6 test classes under test/
scope/check-theme-wiring.sh
```

`test/` covers only `src/.../aa/`, which is pure Java by design; `SessionTest` drives the whole
state machine with a scripted phone and a real server-side `SSLEngine`.

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
  UsbLink.java       UsbManager: find, switch, open, bulk read/write
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

- **Stage 2, wireless Android Auto**: Bluetooth RFCOMM bootstrap (the phone learns the head
  unit's Wi-Fi credentials over BT), then the same session over a TCP socket instead of USB.
  `Session` is transport-agnostic for that reason.
- **Stage 3, CarPlay**: the unit has an MFi authentication chip. That path depends on
  `ZLINK_REWRITE.md` in device-reveng recovering how the OEM app reaches it.
- Also missing from stage 1: microphone (AV input channel), steering-wheel keys as button
  events, Bluetooth channel for hands-free pairing, night mode from the car.
