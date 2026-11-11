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
| R2 | Three spots found in the seed (closest jagged/frozen/stony peaks summit orbit, an open-water run into the low sun, a forest canopy skim). Per flight: fly there, wait for every section, rehearse the move at 2.5x, then record 11 s. Segments `R2_1..3` are joined into `R2.mp4`. |
| R3 | Shaders off. Opens Iris's shader screen, clicks Origin's **Download Shaders**, scrolls, clicks Download on Complementary Reimagined (a real Modrinth download), back to Iris, selects it, Apply, Done. |
| R4 | Opens the resource pack screen, clicks **Browse Packs**, scrolls. |
| R5 | FPS counter on, 20 s of play first (FPS Coach averages the last 10 s), slow pan, then opens FPS Coach. |
| R6 | Mod menu, then the HUD editor; drags Key Strokes and CPS (`ORIGIN_AD_KEYS`/`ORIGIN_AD_CPS` = grab x,y,drop x,y in window px). |
| R7 | In the arena: `SocialToast.request` and `.invite` with made-up players (`Mossbrook`, `TidewaterJay`, made-up UUIDs). |
| R8 | Diamond kit in a sky arena; armored zombies/husks/a piglin brute with 10 HP and 1.5 attack in rounds; client AI aims, sprints, strafes, jumps and clicks 9–13 CPS through the real input path. |

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
