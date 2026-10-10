#!/usr/bin/env python3
"""Generate platform icon assets from shared geometry. Requires macOS Command Line Tools."""
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'assets/branding/icon.json'
design = json.loads(SOURCE.read_text())
def path_data(commands, adaptive=False):
    parts = []
    for command, *coords in commands:
        if adaptive:
            coords = [54 + (value - 50) * .85 for value in coords]
        parts.append(command + ','.join(f'{value:g}' for value in coords))
    return ' '.join(parts)
svg_paths = ''.join(f'<path d="{path_data(p["commands"])}" stroke-width="{p["width"]}"/>' for p in design['paths'])
(ROOT/'assets/branding/macbridge-icon.svg').write_text(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" rx="22" fill="{design["background"]}"/><g fill="none" stroke="{design["foreground"]}" stroke-linecap="round" stroke-linejoin="round">{svg_paths}</g></svg>\n')
vector_paths = '\n'.join(f'    <path android:pathData="{path_data(p["commands"], True)}" android:strokeColor="{design["foreground"]}" android:strokeWidth="{p["width"]*.85:g}" android:strokeLineCap="round" android:strokeLineJoin="round" />' for p in design['paths'])
(ROOT/'app/src/main/res/drawable/ic_launcher_foreground.xml').write_text(f'<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n{vector_paths}\n</vector>\n')
(ROOT/'app/src/main/res/drawable/ic_launcher_background.xml').write_text(f'<?xml version="1.0" encoding="utf-8"?>\n<color xmlns:android="http://schemas.android.com/apk/res/android" android:color="{design["background"]}" />\n')
with tempfile.TemporaryDirectory(prefix='macbridge-icons-') as temp:
    binary = Path(temp)/'render-icon'
    subprocess.run(['swiftc','-module-cache-path',str(Path(tempfile.gettempdir())/'macbridge-icon-module-cache'),str(ROOT/'tools/render-icon.swift'),'-o',str(binary)],check=True)
    def render(path, size, shape):
        subprocess.run([str(binary),str(SOURCE),str(path),str(size),shape],check=True)
    render(ROOT/'assets/branding/macbridge-icon.png',1024,'mac')
    for density, size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
        folder=ROOT/f'app/src/main/res/mipmap-{density}'
        render(folder/'ic_launcher.png',size,'square')
        render(folder/'ic_launcher_round.png',size,'round')
    iconset=Path(temp)/'MacBridge.iconset'; iconset.mkdir()
    for size in (16,32,128,256,512):
        render(iconset/f'icon_{size}x{size}.png',size,'mac')
        render(iconset/f'icon_{size}x{size}@2x.png',size*2,'mac')
    subprocess.run(['iconutil','-c','icns',str(iconset),'-o',str(ROOT/'assets/branding/MacBridge.icns')],check=True)
print('Generated Android density/adaptive icons, SVG source preview, and Mac ICNS.')
