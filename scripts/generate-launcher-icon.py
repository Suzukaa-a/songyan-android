#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow>=10"]
# ///
"""Builds the adaptive launcher icon layers from the iOS app icon, so both platforms share one logo.

Usage:
  scripts/generate-launcher-icon.py /path/to/songyan-ios

Reads Apps/HostApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png from the iOS repo and writes
app/src/main/res/drawable-{mdpi..xxxhdpi}/ic_launcher_{background,foreground,monochrome}.webp.

The iOS icon is one flat painting on paper, but launchers crop adaptive icons to their own shapes
(circle on many devices) and move the layers apart, so it is split in two:
- background: a blank piece of the same paper, filling the whole 108 dp layer;
- foreground: the ink and the seal with the paper removed (colour-to-alpha against the paper
  colour), scaled so the farthest mark stays inside the 66 dp safe circle.
The monochrome layer (themed icons, Android 13+) is the foreground's alpha.
"""

import sys
from pathlib import Path

from PIL import Image, ImageFilter

ICON = "Apps/HostApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LAYER_DP = 108
# The seal sits at 0.94 of the half-width from the centre; 0.65 keeps it inside the 33 dp safe radius.
ART_SCALE = 0.65
# A blank, ink-free corner of the painting, used as the paper for the background layer.
PAPER_BOX = (0, 0, 400, 400)
# Paper grain below this alpha is noise, not ink; dropping it keeps the foreground's square edge invisible.
ALPHA_FLOOR = 0.06


def paper_colour(paper: Image.Image) -> tuple[float, float, float]:
    pixels = [paper.getpixel((x, y)) for y in range(paper.height) for x in range(paper.width)]
    return tuple(sorted(p[c] for p in pixels)[len(pixels) // 2] for c in range(3))


def unblend(art: Image.Image, paper: tuple[float, float, float]) -> Image.Image:
    out = Image.new("RGBA", art.size)
    src = art.load()
    dst = out.load()
    for y in range(art.height):
        for x in range(art.width):
            c = src[x, y][:3]
            a = max(max(0.0, 1.0 - c[i] / paper[i]) for i in range(3))
            a = max(0.0, (a - ALPHA_FLOOR) / (1.0 - ALPHA_FLOOR))
            if a <= 0.0:
                dst[x, y] = (0, 0, 0, 0)
                continue
            a = min(1.0, a)
            rgb = tuple(max(0, min(255, round((c[i] - (1.0 - a) * paper[i]) / a))) for i in range(3))
            dst[x, y] = (*rgb, round(a * 255))
    return out


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    ios = Path(sys.argv[1])
    res = Path(__file__).resolve().parent.parent / "app/src/main/res"
    source = Image.open(ios / ICON).convert("RGB")
    paper = source.crop(PAPER_BOX)
    colour = paper_colour(paper)
    for name, scale in DENSITIES.items():
        size = round(LAYER_DP * scale)
        out = res / f"drawable-{name}"
        out.mkdir(exist_ok=True)
        paper.resize((size, size), Image.LANCZOS).save(out / "ic_launcher_background.webp", quality=90)
        art_size = round(size * ART_SCALE)
        art = unblend(source.resize((art_size, art_size), Image.LANCZOS), colour)
        layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        offset = (size - art_size) // 2
        layer.paste(art, (offset, offset))
        layer.save(out / "ic_launcher_foreground.webp", lossless=True)
        mono = Image.new("RGBA", (size, size), (255, 255, 255, 0))
        mono.putalpha(layer.getchannel("A").filter(ImageFilter.SMOOTH))
        mono.save(out / "ic_launcher_monochrome.webp", lossless=True)


if __name__ == "__main__":
    main()
