#!/usr/bin/env python3
"""The gate's decisions that the car follows (owner-approved 2026-10-01). Stdlib only."""
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(__file__))
import ns_report  # noqa: E402

SHA = "9f2c" + "0" * 60


def write(d, name, obj):
    p = os.path.join(d, name)
    with open(p, "w") as f:
        json.dump(obj, f)
    return p


def main():
    d = tempfile.mkdtemp()
    parent = write(d, "parent.json", {"name": "rnnoise", "version": "2026-10-02.1", "default": "car-tuned",
                                      "files": [{"path": "2026-10-02.1/weights.bin", "sha256": SHA, "bytes": 1553664}]})

    m = json.loads(ns_report.rollback("2026-10-03.1", parent))
    assert m["default"] == "standard", "rollback sets the default back on standard"
    assert m["files"][0]["path"] == "2026-10-03.1/weights.bin", "new version folder"
    assert m["files"][0]["sha256"] == SHA, "same weights as the model rolled back"
    assert m["rollback_of"] == "2026-10-02.1", "records what it rolled back"

    yes = write(d, "m1.json", {"verdict": {"baseline_regressed": True}})
    no = write(d, "m2.json", {"verdict": {"baseline_regressed": False}})
    old = write(d, "m3.json", {"verdict": {}})
    assert ns_report.regressed(yes) == "yes"
    assert ns_report.regressed(no) == "no"
    assert ns_report.regressed(old) == "no", "a metrics file from before the check is not a regression"
    print("ok   test_ns_report")


if __name__ == "__main__":
    main()
