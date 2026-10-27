#!/usr/bin/env python3
"""Install rendered title panoramas into the modules that ship them.

  install.py <artifacts-dir> [spot-id ...]

<artifacts-dir> holds one folder per spot as `gh run download` writes them
(panorama-<id>/final/panorama_0..5.png). Each spot's faces are copied into every module in its
spots.json `targets`, as assets/minecraft/textures/gui/title/background/panorama_0..5.png under the
module's client resources (src/client/resources when the module splits source sets, else
src/main/resources). Mod resources sit above vanilla's, so the title screen and every Origin menu
backdrop show them, and a player's own resource pack still wins. Prints the bytes added per module.
"""
import json
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REL = os.path.join("assets", "minecraft", "textures", "gui", "title", "background")


def resources_dir(module):
    base = os.path.join(ROOT, "src", "mods", "versions", module, "src")
    client = os.path.join(base, "client", "resources")
    return client if os.path.isdir(client) else os.path.join(base, "main", "resources")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    arts = sys.argv[1]
    only = set(sys.argv[2:])
    table = json.load(open(os.path.join(ROOT, "tools", "panorama", "spots.json"), encoding="utf-8"))
    for spot in table["spots"]:
        if only and spot["id"] not in only:
            continue
        src = os.path.join(arts, f"panorama-{spot['id']}", "final")
        faces = [os.path.join(src, f"panorama_{i}.png") for i in range(6)]
        if not all(os.path.isfile(f) for f in faces):
            print(f"install: {spot['id']}: no finished faces in {src}, skipped")
            continue
        size = sum(os.path.getsize(f) for f in faces)
        for module in spot["targets"]:
            dst = os.path.join(resources_dir(module), REL)
            os.makedirs(dst, exist_ok=True)
            for i, f in enumerate(faces):
                shutil.copyfile(f, os.path.join(dst, f"panorama_{i}.png"))
            print(f"install: {spot['id']} -> {module}: {size} bytes")


if __name__ == "__main__":
    main()
