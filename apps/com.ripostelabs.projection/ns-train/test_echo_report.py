#!/usr/bin/env python3
"""echo_report on a synthetic take with a known echo: delay, echo return and ERLE. Stdlib only."""
import array
import os
import random
import sys
import tempfile
import wave

sys.path.insert(0, os.path.dirname(__file__))
import echo_report  # noqa: E402

RATE = 16000
SECONDS = 4
DELAY_S = 0.12
ECHO_GAIN = 0.3        # -10.5 dB echo return
CANCELLED_GAIN = 0.03  # a further -30.5 dB: ERLE 30.5 dB


def write(path, samples):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        a = array.array("h", (max(-32768, min(32767, int(s))) for s in samples))
        if sys.byteorder == "big":
            a.byteswap()
        w.writeframes(a.tobytes())


def main():
    rnd = random.Random(3)
    n = RATE * SECONDS
    # Far-end "speech": noise bursts of random length and level, with pauses.
    far = []
    while len(far) < n:
        burst = rnd.randint(1600, 6400)
        level = rnd.choice([0, 0, 3000, 8000])
        far += [rnd.gauss(0, level) for _ in range(burst)]
    far = far[:n]
    d = int(DELAY_S * RATE)
    mic = [ECHO_GAIN * (far[i - d] if i >= d else 0) + rnd.gauss(0, 5) for i in range(n)]
    cancelled = [CANCELLED_GAIN * s for s in mic]

    tmp = tempfile.mkdtemp()
    stem = os.path.join(tmp, "20261002-120000")
    write(stem + "-raw.wav", mic)
    write(stem + "-downlink.wav", far)
    write(stem + "-echo_cancelled.wav", cancelled)
    with open(stem + "-echo.txt", "w") as f:
        f.write("aec ACTIVE erle 20.0 dB, erl 10.5 dB, delay 120 ms, downlink dropped 0\n")

    r = echo_report.report(stem)
    assert abs(r["delay_ms"] - 120) <= 10, r
    assert abs(r["echo_return_db"] - (-10.5)) <= 1.0, r
    assert r["needs_aec"], r
    assert abs(r["erle_db"] - 30.5) <= 1.0, r
    assert "erle 20.0 dB" in r["aec"], r

    # No canceller tap, no text: the report still gives delay and echo return.
    os.remove(stem + "-echo_cancelled.wav")
    os.remove(stem + "-echo.txt")
    r = echo_report.report(stem)
    assert "erle_db" not in r and "aec" not in r, r
    print("ok   test_echo_report")


if __name__ == "__main__":
    main()
