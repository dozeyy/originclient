# Title panoramas

Each version's title screen spins through 6 cube faces (`panorama_0..5.png`) rendered from a real world
that shows that update's content. `spots.json` is the table (one spot per update: seed, place, mobs,
blocks, which module renders it, which modules ship it).

## Render on this PC (the normal way, 2026-10-27)

```
python -u tools/panorama/local_render.py <spot-id ...|all> [--far 64] [--install]
```

Per spot it prepares the capture module's dev game (`src/mods/versions/<capture>/run`, gitignored), opens
Minecraft with Complementary Reimagined + Distant Horizons, builds the scene (`OriginDevPanorama`), waits
until Distant Horizons has generated the land in every direction (`--far` chunks out), takes the 6 faces,
quits, then:

- `finish.py` crops each face to exactly 90 degrees (rendered at FOV 98 in a 1178 px window, centre 1024 px
  kept), shrinks it to `faceSize` and writes `preview.jpg`;
- `seam_check.py` measures every cube edge (exit 1 on a seam or a darkened border);
- `tools/lean/contact_sheet.py` writes `_sheet.jpg`.

Output: `tools/panorama/out/<id>/` (gitignored). `--install` copies the faces into every target module
(`install.py`). Downloads (shader pack, Distant Horizons) are pinned by sha512 in `spots.json` and cached in
`tools/panorama/cache/` (gitignored). Never commit or ship the shader pack; only the renders ship.

Needs: JDK 21 (`C:\Program Files\Java\jdk-21.0.10`), JDK 25 for 26.x (`~\.jdks\jdk-25.0.3+9`), Python 3
with Pillow + numpy. It opens a Minecraft window per spot (Will's OK for this job, 2026-10-27); it never
touches `%LocalAppData%\OriginLauncher`.

## How a spot is placed

`at` (a researched seed's coordinates) or `locate` gives the start; with `biome`, the nearest such biome is
located from there. `open` then searches that radius for the ground spot with the clearest all-round view:
anything rising more than 6 degrees above the eye within 40 blocks (a cliff, a wall of trees) costs score,
a block reaching eye height within 3 blocks costs 30, the update's biome around it earns up to 150 (a spot where
it fills a quarter of the view always wins over one where it doesn't), and face 0 looks where that biome shows
most. `"wet": true` lets the camera stand on shallow water (mangrove swamp). The camera stands on the ground
(eye height); grass and flowers within 3 blocks are cleared so they don't fill the lower half of a face. Mobs
and blocks are placed in front of face 0 (block commands use `execute positioned ^x ^ ^z positioned over
motion_blocking_no_leaves run setblock ~ ~ ~ ...` so they sit on the surface). Underground spots (`"ground":
"floor"`) take the nearest floor spot with nothing solid within 2 blocks, and skip far terrain (no horizon).

## Seams

The old black vertical lines in the sky were the shader's vignette darkening each face's border
(`seam_check.py` measured the border at 0.72-0.84 of the brightness 24 px in). The check fails a wall edge whose
sky-row difference exceeds max(6, 1.5 x the column-to-column noise one pixel inside), or a border darker than
0.93; the old shipped faces fail it, the new ones pass. Now every face is cropped
from a wider render and the pack's vignette, cloud motion and foliage waving are off (`shaderpack.options`).

## CI

`.github/workflows/panoramas.yml` (commit tag `[panorama:<ids>]`) still renders a spot on llvmpipe, without
far terrain; it is the fallback when no Windows PC is available.
