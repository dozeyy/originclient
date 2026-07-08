#!/usr/bin/env python3
"""Shared helper: bake a text string into a smooth white texture with optional
letter-spacing and a soft glow, returning the image + ink-box metrics.

Used by generate_wordmark.py (the "Origin" wordmark). The wordmark must appear
instantly (before Minecraft's own font loads), so it's baked this way -- a fixed
image, not a dynamic glyph atlas, so it carries none of the earlier
custom-font-rendering risk.
"""
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont


def render_text(font, text, letter_spacing_px=0.0, pad=90, glow_blur=40, glow_alpha=0.30):
    """Render `text` (drawn char-by-char with letter_spacing_px between glyphs)
    to an RGBA image cropped to the ink + `pad`. Returns (img, meta) where meta
    has width/height/inkX/inkY/inkWidth/inkHeight."""
    # First pass: pen positions
    margin = pad + 40
    baseline = margin + font.getmetrics()[0]
    positions = []
    pen = 0.0
    for ch in text:
        adv = font.getlength(ch)
        positions.append((ch, pen))
        pen += adv + letter_spacing_px
    total_adv = pen - (letter_spacing_px if text else 0)

    canvas_w = int(total_adv + 2 * margin)
    canvas_h = int(baseline + font.getmetrics()[1] + margin)
    layer = Image.new("L", (max(canvas_w, 1), max(canvas_h, 1)), 0)
    draw = ImageDraw.Draw(layer)
    for ch, pen_x in positions:
        draw.text((margin + pen_x, baseline), ch, fill=255, font=font, anchor="ls")

    # crop to ink + pad
    bbox = layer.getbbox()
    if bbox is None:
        bbox = (0, 0, 1, 1)
    l, t, r, b = bbox
    l = max(0, l - pad); t = max(0, t - pad)
    r = min(layer.width, r + pad); b = min(layer.height, b + pad)
    layer = layer.crop((l, t, r, b))

    ink_l = pad if (bbox[0] - l) >= pad else (bbox[0] - l)
    ink_t = pad if (bbox[1] - t) >= pad else (bbox[1] - t)
    ink_w = bbox[2] - bbox[0]
    ink_h = bbox[3] - bbox[1]

    glow = layer.filter(ImageFilter.GaussianBlur(glow_blur)).point(lambda a: int(a * glow_alpha))
    alpha = ImageChops.lighter(layer, glow)
    white = Image.new("L", layer.size, 255)
    img = Image.merge("RGBA", (white, white, white, alpha))

    meta = {
        "text": text,
        "width": img.width, "height": img.height,
        "inkX": int(ink_l), "inkY": int(ink_t),
        "inkWidth": int(ink_w), "inkHeight": int(ink_h),
    }
    return img, meta


def load_font(rel_path, size):
    from pathlib import Path
    here = Path(__file__).resolve().parent
    return ImageFont.truetype(str((here / rel_path).resolve()), size=size)
