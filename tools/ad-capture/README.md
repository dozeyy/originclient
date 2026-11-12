# Ad capture harness (dev-only, branch `claude/ad-capture`)

Records the launch ad's in-game clips (R2–R8 in `origin-client-ad/v2/SCRIPT.md`) from the 1.21.1 module's own dev
game (`src/mods/versions/1.21.1/run/`, gitignored). Nothing here ships: every hook is behind
`ORIGIN_SHOTS_SET=ad`, which only `capture.py` sets, and this branch is never released.

```
python tools/ad-capture/capture.py R2 --probe      # stills along the three R2 flights (check the spots)
python tools/ad-capture/capture.py R2 R3 R4 R5 R6 R7 R8
python tools/ad-capture/capture.py encode          # raw takes -> origin-client-ad/edit/public/footage/R*.mp4 at 30 fps
```

Raw takes (1920x1080, 60 fps, x264 crf 12) and each run's log land in `origin-client-ad/footage/raw/`.
Put `ComplementaryReimagined_r5.9.3.zip` (sha512 in `tools/panorama/spots.json`) in `tools/ad-capture/cache/` first.

## How it works

- **`OriginDevAd`** (mod, client) opens one fixed world, `origin-ad` (seed `6671238423019257953`, the 1.18 panorama
  seed), runs the clip's server setup (gamerules, time 11800 or `ORIGIN_AD_TIME`, kit, arena) and then its script. All input is scripted
  inside the game: synthetic GLFW events go through Minecraft's own `MouseHandler.onPress/onMove/onScroll` and
  `KeyboardHandler.keyPress`, so menus, Key Strokes and CPS see them exactly like real input.
- **`DevAdInputMixin` / `DevAdKeyboardMixin`** cut the real mouse and keyboard off from the game and cancel
  `grabMouse`/`releaseMouse`, so the OS pointer is never grabbed or moved. **`DevAdWindowMixin`** opens the window
  without focus. **`DevAdMinecraftMixin`** runs the per-frame script before `GameRenderer.render`, grabs the frame
  before `blitToScreen`, and reports the window as active (no unfocused FPS cap, FPS Coach keeps sampling).
- **`OriginDevAdCapture`** reads the finished main render target (so the OS pointer is never in it) through three
  PBOs into ffmpeg at a wall-clock constant frame rate, like a screen recorder: a slot the game missed repeats the
  previous frame, so FPS readouts in the clip stay honest.
- Discord presence is off in ad mode; the social network loop is off because `ORIGIN_SHOTS` is set.

## Clips

| Clip | What the script does |
|---|---|
| R2 | Rev 3: the flights in `CLIP_ENV["R2"]["ORIGIN_AD_FLIGHTS"]`, each pinned by `ORIGIN_AD_RUN_<NAME>=sx,sz,ex,ez,y,pitch[,secs[,yawOff[,yEnd]]]` (a straight dolly over seed terrain, no placed blocks) and timed by `ORIGIN_AD_TIME_<NAME>`. Per flight: fly there, wait until `landed()` (every section built and at least half the render-distance disc loaded), rehearse, record 11 s to `R2_NN_<name>.mp4`. `ORIGIN_AD_VANILLA=<flight>` flies that path again with Iris off (`R2_NN_<name>_vanilla.mp4`, frame-aligned with the shaded take). |
| R3 | Shaders off. Opens Iris's shader screen, clicks Origin's **Download Shaders**, scrolls, clicks Download on Complementary Reimagined (a real Modrinth download), back to Iris, selects it, Apply, Done. |
| R4 | Opens the resource pack screen, clicks **Browse Packs**, scrolls. |
| R5 | F1-clean, spectator, nothing in the hands. FPS Coach opens at 6.6 s; the pointer glides to TRY IT on the clouds tip (`ORIGIN_AD_COACH_X/Y`, default 1331,634) and clicks at 8.1 s. |
| R6 | `ORIGIN_AD_MENU_ONLY=1` (rev 3): the mod menu alone over an F1-clean canopy, then a slow scroll. `prepare()` sets every mod to its Mods.java default plus the shot-11 HUD set (`R6_ON`), so the cards show a real setup, not a wall of DISABLED. Without MENU_ONLY: the old HUD-editor drags (`ORIGIN_AD_EDIT/_KEYS/_CPS`). |
| R7 | In the arena: `SocialToast.request` and `.invite` with made-up players (`Mossbrook`, `TidewaterJay`, made-up UUIDs). |
| R8 | Diamond kit in a sky arena (rev 1-2 only; rev 3 has no gameplay). |
| R9 | Rev 3's keyed HUD: `DevAdGuiMixin` paints 0xFF00FF before the HUD draws, the take is lossless `libx264rgb` (`ORIGIN_AD_LOSSLESS=1`). The modules in `R9_HUD` (FPS, CPS, Clock, Memory, Coordinates, Potion Effects, Armor Status as a column) at 2x with no backgrounds, plus the two toasts at 4.0 s and 5.2 s. `origin-client-ad/v2/capture/key_hud.py` cuts it into per-module PNG sequences. |
| R10 | Rev 4's shader picker: fresh shaderpacks folder, the hero vantage, Origin's own Download Shaders browser, three scripted Download clicks (`ORIGIN_AD_PICKS=slug@y,...`, default BSL / Photon / Solas at x 1306) with real downloads; each click is `mouseTo` + click. Writes `R10.json` (click second, downloaded second, file per pick). |
| R11 | Rev 4's FPS Coach: lossless magenta key with only the FPS module (scale 4) on, render distance 32, at the `RUN_FAR` vantage; TRY IT on the `ORIGIN_AD_COACH_TIP` tip (default renderdistance). Writes `R11.json` with Coach's own before/after and an FPS sample every 0.5 s. Run with `--far 128`. |
| R12 | Rev 4's mod menu: 13 stills of the menu drawn 1920 wide at exact scroll offsets plus `R12.json`, for `stitch_menu.py`. |
| V3_LOAD | Film v3 (`OriginDevAdV3`, all V3 clips: time 10800, hero vantage, a `V3_*.json` sidecar with every pointer position and input event). Two runs: a stage run parks the spectator at the hero view, then the take records from the world-open request through Origin's loading screens until a few seconds after the world draws. |
| V3_TITLE | The title screen alone (no world): the pointer glides to "Origin" beside "Mods" and clicks; the Origin menu holds 2.5 s. Mods at their shipped defaults (as R6/R12). |
| V3_HUD | F1-clean hero view, diamond armor (for Armor Status); the HUD editor opens and FPS, CPS, Coordinates and Armor Status are dragged into a left-middle stack flush with the editor's edge. No backgrounds (`V3_HUD_OPTS`). |
| V3_ITEMSIZE | Items dropped by the script (3 diamonds, an emerald, a gold ingot) on a flat grass patch near `ORIGIN_AD_ITEM_AT`; Item Size's grid opens, "diamond" is typed in search, Diamond picked, the slider dragged 1.00x -> 2.50x, Esc twice, then the items at their sizes. |
| V3_WAYPOINT | A "Home" waypoint at `ORIGIN_AD_WP_AT` (default 1420,1268), the HUD on with only Minimap + Waypoints, a 12 s dolly (`ORIGIN_AD_RUN_WAYPOINT`) toward it. |
| V3_SNAPLOOK | Creative + F1 (spectator's front view is a floating head), a dolly; the Snaplook key is held 4.2-6.9 s (`DevAdKeysMixin` answers `InputConstants.isKeyDown` from the script's held keys). |
| V3_FRIENDS | Hero view, the friend request and party invite toasts (made-up players). HUD on with every module off in spectator, not F1: 1.21.1's toast pass draws nothing under F1. |
| V3_SCREENSHOT | HUD on with every module off, chat visible: F2, T, the pointer clicks the line's Copy Image (a real copy to the clipboard); the take ends 2.5 s after the copy's own "Screenshot copied" line shows, and only then is the clipboard's text put back. |
| V3_MENU_TABS | Menu tour (`Tour`: the pointer goes straight to each target, settles, clicks; every step waits for its screen; a `beats` list in the sidecar). Right Shift opens the quick screen, MODS opens Origin's menu, then SETTINGS, COSMETICS (Back), FRIENDS (Back, made-up demo friends), WAYPOINTS last (its Back leaves the menu). Mods at shipped defaults, HUD modules without backgrounds (`V3_TOURS`). |
| V3_MOD_OPTIONS | Origin's menu, the HUD chip, wheel-scroll to Minimap, its OPTIONS: switch on, Map Height 8 -> 4, Shape Square -> Circle (the live preview changes each time), Back. Minimap reset before and after. |
| V3_MOD_SEARCH | Origin's menu, a click in search, "zoom" typed, the grid filters, Zoom's DISABLED bar clicked to ENABLED (Zoom starts off, put back on after). |
| V3_PAUSE | Esc, the pause menu's "Mods": Mod Menu's list, Sodium (else Iris) selected, its settings opened; Esc twice, then "Origin" opens Origin's menu. |

`--suffix _9x16` names a V3 take `V3_<clip>_9x16` (the portrait takes: `--size 1440x2560`). `capture.py encode-v3 [V3_X ...]` turns
every raw (or only the named) `V3_*.mp4` into `origin-client-ad/edit/public/v3/footage/` at 60 fps (x264 CRF 12) and copies each sidecar.

Scouting: `ORIGIN_AD_SCOUT=list` with `ORIGIN_AD_SCOUT_LIST=name:sx,sz,ex,ez,y,p;...` flies to each run and saves one
still per run (`footage/scout/screenshots/scout_<name>.png`); pick by eye, then pin the winners as `RUN_<NAME>`.

`--packs` records one flight per shader pack (`PACK_FLIGHTS`). `--far N` installs Distant Horizons 3.3.3 (sha512-pinned
Modrinth download, cached in `cache/`, dev `run/mods` only; any other run removes it) with an N-chunk LOD radius, and holds
the game at its first pose until DH's log says generation is done plus 60 s (gate file `<out>/<clip>.far.ok`). Voxy is not
usable here: the local port targets Sodium 0.6.13 / Iris 1.8.8, Origin ships Sodium 0.8.13 / Iris 1.8.14.

`--set ORIGIN_AD_*=...` passes clip tuning (click targets, time of day `ORIGIN_AD_TIME`). Click targets in Origin
windows are logged as `hits on <Screen> (window px)` on the first run of a clip.

## Traps

- `Level.getHeight` lies for unloaded chunks: every spot search forces `level.getChunk` first (server thread).
- In-world clicks need `mouseGrabbed=true` or `onPress` only tries to grab the mouse; the harness mirrors the flag
  (true with no screen, false with one) without ever really grabbing.
- Origin windows (`OriginWindow`) record click targets while rendering, so a click works only after one drawn frame.
- Recipe/advancement toasts from the setup commands are cleared when a take starts (except R7).
- The window must not be focused or Will's keyboard/mouse would drive it: never remove `DevAdWindowMixin`.
- Spot searches (`/locate`-style biome search, peak, river, canopy) start from the world spawn, never the player, so
  every run finds the same spots. R2's water run is pinned by `ORIGIN_AD_WATER` in `capture.py` (picked by eye from a
  probe; the scorer's best run ended at a lava-lit canyon wall).
- Iris 1.8+ draws its own "Download Shaders" row in the shader list (a pinned entry, not a button); `IrisScreenHook`
  points it at Origin's browser. The script finds it by class name (`irisDownloadRow`), list rows by reflection.
- Screen changes (ESC back to Iris, shader reloads) stall the game for a second or two. Timed steps run one per frame
  and a late step pushes the rest back; R3's after-download steps are a chain timed from the previous step, and pointer
  glides start from the frame they are created in. Never schedule a glide and its click at fixed times across a
  screen change: the click lands at the old pointer.
- Logs: Complementary floods `Unknown variable` lines; `capture.py` prints only `[ad]`, exceptions and the lock.
- `ORIGIN_AD_SCOUT=kinds` (rate every candidate of five biome kinds in one world) loads too many chunks and dies with
  OutOfMemoryError on an 8 GB heap; use the `list` scout.
- A chunk wait that only checks `hasRenderedAllSections` passes right after a teleport, before any chunk has arrived
  (blank-sky stills): `landed()` also needs half the render-distance disc loaded.
- R9: Minecraft's vignette is drawn over the key fill in the first seconds (corners a few percent darker), so
  `key_hud.py` keys on magenta hue (G 0, R = B), not the exact colour. R9 keeps the shaders on under the key: with
  them off its FPS readout showed ~2800 (a bare world), next to the Coach's real 265 average.
- `prepare()` writes `config/originclient-mods.json`; module states set for one clip (R9, R6) stay for the next.
