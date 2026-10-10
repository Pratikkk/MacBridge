#!/usr/bin/env python3
"""Read-only idle CPU/frame sample. Leave Home open; this never drives the UI."""
import argparse
import json
import re
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True)
parser.add_argument("--seconds", type=float, default=20)
args = parser.parse_args()
if not 1 <= args.seconds <= 60:
    parser.error("--seconds must be between 1 and 60")
package = "com.aistudio.macbridge.kxmpzq"


def shell(*command):
    return subprocess.check_output([args.adb, "-s", args.serial, "shell", *command], text=True, timeout=10).strip()


def sample():
    pid = shell("pidof", package)
    if not pid.isdigit():
        raise RuntimeError("Expected one running app process")
    def ticks(path):
        fields = shell("run-as", package, "cat", path).rsplit(")", 1)[1].split()
        return int(fields[11]) + int(fields[12])  # proc stat fields 14 + 15
    graphics = shell("dumpsys", "gfxinfo", package)
    def metric(pattern):
        match = re.search(pattern, graphics)
        if not match:
            raise RuntimeError("Missing gfxinfo metric")
        return int(match[1])
    return dict(pid=pid, cpu=ticks(f"/proc/{pid}/stat"), main=ticks(f"/proc/{pid}/task/{pid}/stat"),
                frames=metric(r"Total frames rendered: (\d+)"), jank=metric(r"Janky frames: (\d+)"),
                views=metric(r"Total ViewRootImpl\s+: (\d+)"))


tick_rate = int(shell("getconf", "CLK_TCK"))
before = sample()
start = time.monotonic()
time.sleep(args.seconds)
after = sample()
elapsed = time.monotonic() - start
if before["pid"] != after["pid"]:
    raise RuntimeError("App restarted during sampling; repeat the measurement")
if min(before["views"], after["views"]) < 1:
    raise RuntimeError("No attached app window; foreground idle result unavailable")
print(json.dumps(dict(elapsed_seconds=round(elapsed, 2),
    new_frames=after["frames"] - before["frames"], new_janky_frames=after["jank"] - before["jank"],
    process_cpu_ms=(after["cpu"] - before["cpu"]) * 1000 / tick_rate,
    main_thread_cpu_ms=(after["main"] - before["main"]) * 1000 / tick_rate), indent=2))
