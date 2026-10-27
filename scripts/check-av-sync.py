#!/usr/bin/env python3
"""Independently decode the timed flash/pulse fixture's audio with FFmpeg."""
import argparse
import array
import hashlib
import json
import pathlib
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('video', type=pathlib.Path)
parser.add_argument('--output', required=True, type=pathlib.Path)
args = parser.parse_args()
raw = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(args.video), '-vn', '-ac', '1', '-ar', '48000', '-f', 'f32le', '-'])
samples = array.array('f')
samples.frombytes(raw)
if sys.byteorder != 'little':
    samples.byteswap()
onsets = []
previous = -1000
for i, sample in enumerate(samples):
    if abs(sample) > .1:
        if i - previous > 480:
            onsets.append(round(i * 1_000_000 / 48000))
        previous = i
expected = [0, 250_000, 750_000, 958_333]
assert len(onsets) == len(expected), f'Expected four pulses, found {onsets}'
measurements = [dict(expected_onset_us=e, decoded_onset_us=a, difference_us=a-e) for e, a in zip(expected, onsets)]
tail = any(abs(x) > .1 for x in samples[47_400:47_900])
report = dict(method='Independent FFmpeg mono float32 48 kHz decode, threshold 0.1; video flash onsets checked by Android instrumentation.',
              sha256=hashlib.sha256(args.video.read_bytes()).hexdigest(), onsets=measurements, tail_after987ms_audible=tail)
args.output.write_text(json.dumps(report, indent=2) + '\n')
assert tail and all(abs(x['difference_us']) <= 20_000 for x in measurements), report
print(f'A/V pulse check passed; maximum absolute offset {max(abs(x["difference_us"]) for x in measurements)} us')
