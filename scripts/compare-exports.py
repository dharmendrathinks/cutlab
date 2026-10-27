#!/usr/bin/env python3
"""Compare the fixed reference export with a CutLab baseline; requires ffmpeg/ffprobe."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("reference", type=pathlib.Path)
parser.add_argument("candidate", type=pathlib.Path)
parser.add_argument("--output", type=pathlib.Path)
args = parser.parse_args()


def inspect(path):
    probe = subprocess.check_output([
        "ffprobe", "-v", "error", "-count_frames", "-show_entries",
        "stream=codec_name,width,height,r_frame_rate,sample_rate,channels,start_time,duration,nb_read_frames",
        "-of", "json", str(path),
    ], text=True)
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"sha256": digest.hexdigest(), **json.loads(probe)}


reference, candidate = inspect(args.reference), inspect(args.candidate)
comparison = subprocess.run([
    "ffmpeg", "-hide_banner", "-nostdin", "-i", str(args.reference), "-i", str(args.candidate),
    "-lavfi", "[0:v]setpts=PTS-STARTPTS,scale=640:360,format=yuv420p[a];"
    "[1:v]setpts=PTS-STARTPTS,scale=640:360,format=yuv420p[b];[a][b]ssim",
    "-an", "-f", "null", "-",
], capture_output=True, text=True, check=True)
match = re.search(r"SSIM Y:([\d.]+).*?U:([\d.]+).*?V:([\d.]+).*?All:([\d.]+)", comparison.stderr)
if match is None:
    raise SystemExit("FFmpeg did not produce an SSIM result")
report = {
    "reference": reference,
    "candidate": candidate,
    "ssim_640x360": dict(zip(("Y", "U", "V", "all"), map(float, match.groups()))),
    "limits": "Unedited fixture only. Compare durations and frame counts separately; SSIM is not whole-app parity. Audio synchronization requires the timed flash/pulse test.",
}
text = json.dumps(report, indent=2) + "\n"
if args.output:
    args.output.write_text(text)
else:
    print(text, end="")
