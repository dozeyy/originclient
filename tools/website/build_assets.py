#!/usr/bin/env python3
"""Build every asset the website uses from its REAL source, so the site looks
exactly like the launcher and the game and never drifts from them.

Origin's own art (straight from this repo):
  website/assets/icons/launcher-*.svg <- design/icon-proposals/origin-icon-system-v1/svg
                                       (the launcher's own icon masters)
  website/assets/fonts/monocraft.woff2 <- tools/font-atlas/fonts/Monocraft.ttf (the launcher's face)
  website/assets/fonts/inter-*.woff2   <- tools/font-atlas/fonts/Inter-*.ttf (the site's body text; also Origin's in-game menu face)

Minecraft's own default Java textures (vanilla 1.21.1, the version Origin is
perfected on first). Read from the official client jar when one is given,
otherwise from a verbatim mirror of that jar's assets:
  website/assets/blocks/*.png   block textures for the island; grass/leaves/water tinted
                                to the plains colours the way the game tints them at render time

  website/js/vendor/three.module.min.js <- three.js (MIT), pinned

Re-run after changing any source:
  python3 tools/website/build_assets.py
  python3 tools/website/build_assets.py --client-jar "%APPDATA%/.minecraft/versions/1.21.1/1.21.1.jar"
"""
from __future__ import annotations

import argparse
import io
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path

from PIL import Image

REPO = Path(__file__).resolve().parents[2]
SITE = REPO / "website"
ICON_MASTERS = REPO / "design" / "icon-proposals" / "origin-icon-system-v1" / "svg"
FONTS = REPO / "tools" / "font-atlas" / "fonts"
# Inter weights the in-game menus draw with (OriginText regular / semibold / bold).
INTER_WEIGHTS = (400, 600, 700)

MC_VERSION = "1.21.1"
# Verbatim copy of the 1.21.1 client jar's assets/ folder, pinned to a commit.
MIRROR = "https://github.com/InventivetalentDev/minecraft-assets"
MIRROR_COMMIT = "aef047f783f44424a591eeecf6b230d5bb0c8095"
THREE_VERSION = "0.170.0"

# Minecraft's plains-biome colours (what the game multiplies its grey grass,
# foliage and water textures by).
GRASS = (145, 189, 89)
FOLIAGE = (119, 171, 47)
WATER = (63, 118, 228)

# name on the site -> (texture under textures/block, tint or None)
BLOCKS = {
    "grass_top": ("grass_block_top", GRASS),
    "dirt": ("dirt", None),
    "stone": ("stone", None),
    "cobblestone": ("cobblestone", None),
    "gravel": ("gravel", None),
    "coarse_dirt": ("coarse_dirt", None),
    "oak_log": ("oak_log", None),
    "oak_log_top": ("oak_log_top", None),
    "oak_leaves": ("oak_leaves", FOLIAGE),
    "oak_planks": ("oak_planks", None),
    "emerald_ore": ("emerald_ore", None),
    "diamond_ore": ("diamond_ore", None),
    "glowstone": ("glowstone", None),
    "short_grass": ("short_grass", GRASS),
    "dandelion": ("dandelion", None),
    "poppy": ("poppy", None),
    "torch": ("torch", None),
    "water": ("water_still", WATER),
    "crafting_table_top": ("crafting_table_top", None),
    "crafting_table_front": ("crafting_table_front", None),
    "crafting_table_side": ("crafting_table_side", None),
}

TEXTURE_PATHS = ["textures/block"]


def run(*cmd: str, cwd: Path | None = None) -> None:
    subprocess.run(cmd, cwd=cwd, check=True)


class Assets:
    """Reads assets/minecraft/<path> from the client jar or the mirror checkout."""

    def __init__(self, work: Path, client_jar: Path | None):
        self.jar = zipfile.ZipFile(client_jar) if client_jar else None
        if not self.jar:
            root = work / "mc"
            run("git", "clone", "-q", "--filter=blob:none", "--no-checkout", "--sparse", MIRROR, str(root))
            run("git", "sparse-checkout", "set", *[f"assets/minecraft/{p}" for p in TEXTURE_PATHS], cwd=root)
            run("git", "checkout", "-q", MIRROR_COMMIT, cwd=root)
            self.root = root / "assets" / "minecraft"

    def image(self, path: str) -> Image.Image:
        if self.jar:
            return Image.open(io.BytesIO(self.jar.read(f"assets/minecraft/{path}"))).convert("RGBA")
        return Image.open(self.root / path).convert("RGBA")


def fresh(folder: Path) -> Path:
    if folder.exists():
        shutil.rmtree(folder)
    folder.mkdir(parents=True)
    return folder


def tint(img: Image.Image, colour: tuple[int, int, int]) -> Image.Image:
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            px[x, y] = (r * colour[0] // 255, g * colour[1] // 255, b * colour[2] // 255, a)
    return img


def first_frame(img: Image.Image) -> Image.Image:
    """Animated textures are a vertical strip of square frames."""
    return img.crop((0, 0, img.width, img.width)) if img.height > img.width else img


def build_textures(mc: Assets) -> None:
    out = fresh(SITE / "assets" / "blocks")
    for name, (texture, colour) in BLOCKS.items():
        img = first_frame(mc.image(f"textures/block/{texture}.png"))
        if colour:
            img = tint(img, colour)
        img.save(out / f"{name}.png", optimize=True)
    # The grass side is dirt with a grey grass fringe the game tints on top.
    side = mc.image("textures/block/grass_block_side.png")
    side.alpha_composite(tint(mc.image("textures/block/grass_block_side_overlay.png"), GRASS))
    side.save(out / "grass_side.png", optimize=True)

    (SITE / "assets" / "MINECRAFT-ASSETS.txt").write_text(
        f"assets/blocks are Minecraft's default Java textures from Minecraft {MC_VERSION}, (c) Mojang AB.\n"
        "Grass, leaves and water are tinted to the plains colours the game applies at render time.\n"
        "Generated by tools/website/build_assets.py. Origin is not an official Minecraft product and is not\n"
        "approved by or associated with Mojang or Microsoft.\n", encoding="utf-8")


def build_icons() -> None:
    out = fresh(SITE / "assets" / "icons")
    for svg in sorted(ICON_MASTERS.glob("launcher-*.svg")):
        # currentColor everywhere, so CSS tints them exactly like the game does.
        text = re.sub(r'\scolor="#[0-9A-Fa-f]+"', "", svg.read_text(encoding="utf-8"))
        (out / svg.name).write_text(text, encoding="utf-8")


def build_fonts() -> None:
    from fontTools.ttLib import TTFont
    out = SITE / "assets" / "fonts"
    out.mkdir(parents=True, exist_ok=True)
    for source, target in [("Monocraft.ttf", "monocraft.woff2"),
                           *[(f"Inter-{w}.ttf", f"inter-{w}.woff2") for w in INTER_WEIGHTS]]:
        font = TTFont(str(FONTS / source))
        font.flavor = "woff2"
        font.save(str(out / target))
    shutil.copy(FONTS / "Monocraft-LICENSE.txt", out / "Monocraft-LICENSE.txt")
    shutil.copy(FONTS / "OFL-LICENSE.txt", out / "Inter-LICENSE.txt")


def build_three(work: Path) -> None:
    run("npm", "pack", f"three@{THREE_VERSION}", "--silent", cwd=work)
    with tarfile.open(work / f"three-{THREE_VERSION}.tgz") as tar:
        tar.extractall(work, filter="data")
    out = SITE / "js" / "vendor"
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy(work / "package" / "build" / "three.module.min.js", out / "three.module.min.js")
    shutil.copy(work / "package" / "LICENSE", out / "three-LICENSE.txt")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--client-jar", type=Path, help=f"official Minecraft {MC_VERSION} client jar to read textures from")
    args = ap.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        build_icons()
        build_fonts()
        mc = Assets(work, args.client_jar)
        build_textures(mc)
        build_three(work)
    print("website assets rebuilt")


if __name__ == "__main__":
    main()
