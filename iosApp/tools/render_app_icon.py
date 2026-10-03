#!/usr/bin/env python3
"""Renders the iPhone app icon from the Android launcher icon.

The Android adaptive icon (app/src/main/res/drawable/ic_launcher_background.xml
and ic_launcher_foreground.xml) is a 108x108 vector whose path data is SVG
syntax. Launchers show only its central 72x72 part, so that part is rendered
here as a 1024x1024 opaque PNG (Xcode 14+ single-size app icon; iOS applies
its own rounded mask, and App Store rules forbid an alpha channel).

Usage (from the repository root):
    pip install cairosvg pillow
    python3 iosApp/tools/render_app_icon.py
"""

from __future__ import annotations

import io
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import cairosvg
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
DRAWABLES = ROOT / "app" / "src" / "main" / "res" / "drawable"
LAYERS = ["ic_launcher_background.xml", "ic_launcher_foreground.xml"]
OUTPUT = ROOT / "iosApp" / "LuminaReader" / "Assets.xcassets" / "AppIcon.appiconset" / "AppIcon-1024.png"
SIZE = 1024
ANDROID = "{http://schemas.android.com/apk/res/android}"
VIEWPORT = 108.0
SAFE_ZONE = 72.0  # visible part of an adaptive icon


def svg_color(android_color: str) -> tuple[str, float]:
    """#RRGGBB or #AARRGGBB -> (#RRGGBB, opacity)."""
    value = android_color.strip().lstrip("#")
    if len(value) == 8:
        return "#" + value[2:], int(value[:2], 16) / 255.0
    if len(value) == 6:
        return "#" + value, 1.0
    raise ValueError(f"unsupported colour {android_color!r}")


def svg_paths(vector_xml: Path) -> list[str]:
    root = ET.parse(vector_xml).getroot()
    viewport = (float(root.get(ANDROID + "viewportWidth")), float(root.get(ANDROID + "viewportHeight")))
    if viewport != (VIEWPORT, VIEWPORT):
        raise ValueError(f"{vector_xml.name}: expected a {VIEWPORT:g}x{VIEWPORT:g} viewport, got {viewport}")
    elements = []
    for path in root.iter("path"):
        attrs = {"d": path.get(ANDROID + "pathData")}
        fill = path.get(ANDROID + "fillColor")
        stroke = path.get(ANDROID + "strokeColor")
        if fill:
            attrs["fill"], attrs["fill-opacity"] = svg_color(fill)
        else:
            attrs["fill"] = "none"
        if stroke:
            attrs["stroke"], attrs["stroke-opacity"] = svg_color(stroke)
            attrs["stroke-width"] = path.get(ANDROID + "strokeWidth", "0")
            attrs["stroke-linecap"] = path.get(ANDROID + "strokeLineCap", "butt")
            attrs["stroke-linejoin"] = path.get(ANDROID + "strokeLineJoin", "miter")
        elements.append("<path " + " ".join(f'{k}="{v}"' for k, v in attrs.items()) + "/>")
    return elements


def main() -> int:
    offset = (VIEWPORT - SAFE_ZONE) / 2
    paths = [p for layer in LAYERS for p in svg_paths(DRAWABLES / layer)]
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{SIZE}" height="{SIZE}" '
        f'viewBox="{offset:g} {offset:g} {SAFE_ZONE:g} {SAFE_ZONE:g}">' + "".join(paths) + "</svg>"
    )
    png = cairosvg.svg2png(bytestring=svg.encode("utf-8"), output_width=SIZE, output_height=SIZE)
    image = Image.open(io.BytesIO(png)).convert("RGBA")
    opaque = Image.new("RGB", image.size, svg_color("#0A0D16")[0])
    opaque.paste(image, mask=image.getchannel("A"))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    opaque.save(OUTPUT, format="PNG", optimize=True)
    print(f"wrote {OUTPUT.relative_to(ROOT)} ({SIZE}x{SIZE}, {opaque.mode})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
