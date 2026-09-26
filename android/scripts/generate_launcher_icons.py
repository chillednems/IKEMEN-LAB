"""Resize the approved Fighter Lab art into Android launcher resources.

Requires Pillow. This script only scales and positions the supplied source art.
"""
from pathlib import Path
from PIL import Image

ANDROID = Path(__file__).resolve().parents[1]
SOURCE = ANDROID / "artwork" / "fighter-lab-source.png"
RES = ANDROID / "app" / "src" / "main" / "res"

source = Image.open(SOURCE).convert("RGBA")
for suffix, legacy_size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    adaptive_size = legacy_size * 9 // 4
    foreground = Image.new("RGBA", (adaptive_size, adaptive_size), (0, 0, 0, 0))
    art_size = adaptive_size * 3 // 4
    art = source.resize((art_size, art_size), Image.Resampling.LANCZOS)
    offset = (adaptive_size - art_size) // 2
    foreground.alpha_composite(art, (offset, offset))
    drawable = RES / f"drawable-{suffix}"
    drawable.mkdir(parents=True, exist_ok=True)
    foreground.save(drawable / "ic_launcher_foreground.png", optimize=True)

    legacy = Image.new("RGBA", (legacy_size, legacy_size), (17, 29, 39, 255))
    art_size = round(legacy_size * 0.9)
    art = source.resize((art_size, art_size), Image.Resampling.LANCZOS)
    offset = (legacy_size - art_size) // 2
    legacy.alpha_composite(art, (offset, offset))
    mipmap = RES / f"mipmap-{suffix}"
    mipmap.mkdir(parents=True, exist_ok=True)
    legacy.convert("RGB").save(mipmap / "ic_launcher.png", optimize=True)
