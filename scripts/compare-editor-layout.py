#!/usr/bin/env python3
"""Measure geometry on the fixed 1080x2400 color-bar editor fixture, not overall fidelity.
Requires Pillow. Input screenshots are read only; the report embeds their original bytes.
"""
import argparse
import base64
import json
from pathlib import Path
from PIL import Image


def measure(path):
    image = Image.open(path).convert('RGB')
    width, height = image.size
    def colored(x, y):
        rgb = image.getpixel((x, y))
        return max(rgb)-min(rgb) > 90
    preview = [y for y in range(int(height*.12), int(height*.55))
               if sum(colored(x, y) for x in range(0, width, 8)) > width/8*.5]
    if not preview:
        raise ValueError(f'Color-bar preview not found: {path}')
    timeline = [y for y in range(int(height*.60), int(height*.79))
                if sum(colored(x, y) for x in range(width//2+8, int(width*.85), 4)) > width*.35/4*.55]
    result = dict(width=width,height=height,preview_top=min(preview),preview_bottom=max(preview),
                  main_clip_top=min(timeline) if timeline else None,
                  main_clip_bottom=max(timeline) if timeline else None)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('reference',type=Path)
    parser.add_argument('before',type=Path)
    parser.add_argument('after',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    paths = {key:getattr(args,key) for key in ('reference','before','after')}
    metrics = {key:measure(path) for key,path in paths.items()}
    reference = metrics['reference']
    for key in ('before','after'):
        if (metrics[key]['width'],metrics[key]['height']) != (reference['width'],reference['height']):
            raise ValueError('Screenshots must use the same device dimensions')
        metrics[key]['absolute_offset_px'] = {name:abs(metrics[key][name]-reference[name])
            for name in ('preview_top','preview_bottom','main_clip_top','main_clip_bottom')
            if metrics[key][name] is not None and reference[name] is not None}
    report = {'scope':'Fixed color-bar editor at t=0 on the same emulator. Geometry only; not a whole-app similarity score.', 'measurements':metrics}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2)+'\n')
    cards = ''.join(f'<section><h2>{key.title()}</h2><img alt="{key} editor" src="data:image/png;base64,{base64.b64encode(path.read_bytes()).decode()}"></section>' for key,path in paths.items())
    html = '<!doctype html><html><meta charset="utf-8"><title>CutLab editor comparison</title><style>body{font:16px system-ui;background:#151515;color:#eee;margin:24px}main{display:flex;gap:20px}section{flex:1;min-width:0}h2{font-size:16px}img{width:100%;height:auto}p{max-width:900px;color:#bbb}</style><h1>Editor layout comparison</h1><p>'+report['scope']+'</p><main>'+cards+'</main></html>'
    args.output.with_suffix('.html').write_text(html)
    print(json.dumps(report,indent=2))

if __name__ == '__main__':
    main()
