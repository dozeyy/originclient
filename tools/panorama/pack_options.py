#!/usr/bin/env python3
"""Print the shader pack's option overrides (spots.json shaderpack.options) as Iris's per-pack
options file (shaderpacks/<pack file>.txt, one KEY=value per line).

  pack_options.py <spots.json>
"""
import json
import sys

pack = json.load(open(sys.argv[1], encoding="utf-8"))["shaderpack"]
for key, value in pack.get("options", {}).items():
    print(f"{key}={value}")
