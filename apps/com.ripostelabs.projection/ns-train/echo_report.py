#!/usr/bin/env python3
"""Echo report for one call audio check (Projection > Mic > Call audio check). Stdlib only.

  echo_report.py FOLDER/STAMP        reads STAMP-raw.wav, STAMP-downlink.wav and, when there,
                                     STAMP-echo_cancelled.wav and STAMP-echo.txt

Prints, from the take's own audio:

  delay        downlink to its echo in the raw mic, 10 ms steps (energy envelopes)
  echo return  how loud the speakers come back into the raw mic while the far end talks;
               above about -20 dB the far end hears itself without an echo canceller
  ERLE         how much of that echo the canceller removed (raw over echo_cancelled, same
               frames); 0 dB when the canceller was off
  aec          AEC3's own figures at the end of the take, from STAMP-echo.txt

Frames are 10 ms. "Far end talking" is the loudest 30 % of downlink frames, shifted by the
delay. Double talk on those frames lowers ERLE: it is a floor, not a ceiling.
"""
import array
import math
import os
import sys
import wave

FRAME_S = 0.01
MAX_DELAY_FRAMES = 50          # 500 ms, AEC3's delay estimator range
FAR_TALK_FRACTION = 0.3
NEEDS_AEC_DB = -20.0
FLOOR = 1e-10


def frames_db(path):
    """10 ms frame energies in dB of a 16-bit WAV, channels averaged."""
    with wave.open(path, "rb") as w:
        rate, ch = w.getframerate(), w.getnchannels()
        pcm = array.array("h", w.readframes(w.getnframes()))
    if sys.byteorder == "big":
        pcm.byteswap()
    step = int(rate * FRAME_S) * ch
    out = []
    for i in range(0, len(pcm) - step + 1, step):
        e = sum(s * s for s in pcm[i: i + step]) / step / (32768.0 ** 2)
        out.append(10 * math.log10(e + FLOOR))
    return out


def delay(mic, far):
    """The lag (frames) where the downlink's envelope best explains the mic's."""
    best, best_lag = -math.inf, 0
    n = min(len(mic), len(far))
    for lag in range(0, min(MAX_DELAY_FRAMES, n - 1) + 1):
        a = mic[lag:n]
        b = far[: n - lag]
        ma, mb = sum(a) / len(a), sum(b) / len(b)
        cov = sum((x - ma) * (y - mb) for x, y in zip(a, b))
        va = math.sqrt(sum((x - ma) ** 2 for x in a)) or 1.0
        vb = math.sqrt(sum((y - mb) ** 2 for y in b)) or 1.0
        c = cov / (va * vb)
        if c > best:
            best, best_lag = c, lag
    return best_lag


def median(xs):
    s = sorted(xs)
    return s[len(s) // 2] if s else float("nan")


def report(stem):
    mic = frames_db(stem + "-raw.wav")
    far = frames_db(stem + "-downlink.wav")
    lag = delay(mic, far)
    n = min(len(mic), len(far) + lag)
    shifted = [-200.0] * lag + far
    threshold = sorted(shifted[:n])[int(n * (1 - FAR_TALK_FRACTION))]
    talk = [i for i in range(n) if shifted[i] >= threshold and shifted[i] > -90]
    out = {"delay_ms": lag * FRAME_S * 1000}
    if not talk:
        out["note"] = "no far-end speech in the take"
        return out

    out["echo_return_db"] = median([mic[i] - shifted[i] for i in talk])
    out["needs_aec"] = out["echo_return_db"] > NEEDS_AEC_DB
    if os.path.exists(stem + "-echo_cancelled.wav"):
        aec = frames_db(stem + "-echo_cancelled.wav")
        out["erle_db"] = median([mic[i] - aec[i] for i in talk if i < len(aec)])
    if os.path.exists(stem + "-echo.txt"):
        with open(stem + "-echo.txt") as f:
            out["aec"] = f.read().strip()
    return out


def main():
    stem = sys.argv[1]
    for suffix in ("-raw.wav", "-raw"):
        if stem.endswith(suffix):
            stem = stem[: -len(suffix)]
    r = report(stem)
    print(f"delay: {r['delay_ms']:.0f} ms")
    if "note" in r:
        print(r["note"])
        return
    print(f"echo return (raw mic over downlink, far end talking): {r['echo_return_db']:.1f} dB")
    if "erle_db" in r:
        print(f"ERLE (raw over echo_cancelled, same frames): {r['erle_db']:.1f} dB")
    if "aec" in r:
        print(r["aec"])
    print("verdict:", "echo reaches the mic: keep echo cancellation on" if r["needs_aec"]
          else "little echo in the mic: the phone's own processing is enough")


if __name__ == "__main__":
    main()
