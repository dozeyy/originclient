# Origin Client — development report

Branch `claude/origin-client-development-4790bv` · 2026-10-18 · **1.21.1 only** (current focus; nothing in `shared/`).

## Done
**Resource Pack Hub** (`packs/`). It opens from a new "Browse Packs" button on Minecraft's Resource Packs screen.
- **Browse tab**: live Modrinth search for the running game version.
  - Search waits 350 ms after you stop typing.
  - Sort by downloads, relevance, follows, newest or updated.
  - Resolution filter uses the author-declared Modrinth tags only.
  - Category chips: PvP (Modrinth's `combat`), Vanilla+, Realistic, Simplistic, Themed, Tweaks, Utility, Decoration.
  - Each row has a preview, author, downloads, resolution, categories, license and a Modrinth page link.
  - Infinite scroll, 40 per page, up to 1000 results.
  - Error, retry, rate-limit and offline states. Offline uses a disk-cached copy and labels it as such.
- **Install**:
  - Downloads only from Modrinth's https hosts. A redirect off those hosts is refused.
  - Download goes to a `.part` file, with live progress and Cancel.
  - The sha512/sha1 hash is verified before anything is kept.
  - `pack.mcmeta` and zip-slip checks run before the file is moved in.
  - New files get a unique name, so nothing is ever overwritten. A byte-identical pack that's already installed is recognised and not downloaded twice.
  - A pack made for another version is flagged.
- **Installed tab**: every zip and pack folder.
  - Turn packs on/off and reorder them (top wins).
  - Update: hand-added Modrinth packs are recognised by hash too.
  - Uninstall asks first, and the hub never deletes folders.
  - Presets: save, apply, delete.
  - Open folder.
- **Applying changes**: edits are staged. On close they are written into the vanilla screen's own selection, so vanilla's Done does the single reload. Built-in and locked packs keep their place.
  - Windows locks loaded packs, so their uninstall waits for the next resource reload or game start.
  - If the accessor mixin ever fails, the hub falls back to install-only with a notice.

**FPS module: frame pacing.** Three new options, all off by default:
- Show 1% Low
- Show Frame Time (ms)
- Frame Time Graph: red bar = hitch, gold bar = a hitch that a JVM garbage-collection pause overlapped.

`FrameStats` records each frame at `GameRenderer.render` HEAD: one `nanoTime` call and one array store, no allocation. The sort and the GC lookup run only 4×/s or on spike frames.

## Origin Render (experimental, off by default)
**What it is.** GPU occlusion culling for Sodium's opaque terrain. Settings → Performance → "Origin Render (Experimental)".
- Sodium still picks the sections in view on the CPU (frustum and cave culling).
- The graphics card then skips sections hidden behind terrain *already drawn this frame*. It uses a two-phase hierarchical-Z test:
  1. Draw what was visible last frame.
  2. Build a depth pyramid.
  3. Test every section and draw what's newly visible.
- Because the test is against this frame's real depth, nothing should pop in when you turn.
- Cutout terrain (leaves, grass) is tested against the finished solid depth. Translucent terrain is untouched.

**How it plugs in.**
- One HEAD inject on Sodium 0.6.13's `DefaultChunkRenderer.render`.
- It uses Sodium's own vertex arrays, index buffer, program and begin/end, so Iris's hooks still run. Only the draw call changes: GPU-written `glMultiDrawElementsIndirect`, still one draw per region.
- Written from scratch. Nothing is copied from Sodium (PolyForm Shield), Nvidium (NVIDIA-only) or Voxy (All-Rights-Reserved).

**When it falls back to plain Sodium.** No crash, and the F3 line says why:
- setting off;
- a shaderpack is on (Iris owns the pipeline; mandate 3);
- no OpenGL 4.5 (e.g. macOS);
- terrain isn't drawing to the main screen;
- any exception or GL error. It then stays off until restart, and turning it on in the menu explains why in chat.

**Proven so far**, on a real GL 4.5 driver (Mesa) through `tools/origin-render-tests` (37 passed):
- the depth pyramid matches a CPU reference;
- across 36 000 random sections (including ones placed right at occluder edges), every section it culled was confirmed hidden by a GPU occlusion query, so 0 were wrongly hidden;
- GL state that Minecraft and Sodium cache is untouched;
- indirect draws render.

Deliberately broken shaders (no depth margin, too-coarse pyramid level, one-pixel sampling) are caught by these tests.

**Not yet proven.** Whether it makes the game faster. It saves vertex and fragment work on hidden terrain (caves, hills, towns), but costs a small compute pass. Gains are likely only when the GPU is the bottleneck. Measure with the frame-time graph; no FPS claims until then.

## Performance
No blind optimisations and no FPS claims. The stack (Sodium, Lithium, FerriteCore, ImmediatelyFast, ModernFix, Krypton) already covers what Lunar, Feather and Badlion ship. The new frame graph and 1% low are the measuring tools that should come first.

## Bugs fixed (found in self-review)
- Catalog paging never reset after a query change.
- A reinstalled pack could stay queued for deletion and be removed later.
- Decompression-bomb images could exhaust the heap (decode now limited to 4096 px per side / 16 MP).
- An update retired the old file before confirming the new one was on disk.
- A failed local scan hid the last network message.

## Build / test
- `python3 tools/packhub-tests/run.py --gson <gson.jar>`: **73 passed, 0 failed**. Covers file names, mcmeta, zips, search/URL building, the index, ordering, the installer (local HTTP server), the library scan and FrameStats.
- `python3 tools/origin-render-tests/run.py`: **37 passed, 0 failed** (GPU culler on Mesa GL 4.5; downloads LWJGL itself).
- **Not compiled against Minecraft**: Fabric/Mojang maven is 403 in the sandbox. Pushing the branch lets build-check CI compile it. The Origin Render glue was type-checked by javac against stubs of the exact Sodium 0.6.13 / Iris signatures.
- An independent review judged the game-side API names right. It flagged only `PackSelectionScreen.model` / `reload()` as "verify at mixin apply".

## Needs Will's in-game test (1.21.1, `runClient`)
1. Zero `Mixin apply ... failed` lines, especially `PackSelectionScreenAccessor`.
2. Options → Resource Packs → Browse Packs. Search, filters, scroll past 40, Install, Cancel, Enable, close, Done: the pack is applied.
3. Installed tab:
   - Reorder and toggle, then close: vanilla's list matches.
   - Programmer Art / High Contrast stay in place.
   - Uninstall an *enabled* pack: it's gone after Done on Windows.
4. Toggle a pack in vanilla's list *first*, then open the hub and come back: the toggle must survive.
5. FPS module → Frame Times options; the HUD editor box should size to include the graph.
6. Origin Render: zero `Mixin apply ... failed` for `Sodium*` / `DebugScreenOriginRenderMixin`. Turn it on, press F3: "Origin Render: on, drawing N of M solid sections".
   - Spin fast, fly, and walk through caves and forests. There must be no holes, flicker or late-appearing blocks.
   - Load a shaderpack: F3 says paused and the shaders look normal. Unload it: it resumes.
   - Compare FPS and the frame-time graph with it on and off at the same spot.

## Main files
- `src/mods/versions/1.21.1/src/client/java/com/origin/client/client/packs/*`
- `mixin/PackSelectionScreenAccessor.java` + `originclient.client.mixins.json`
- `OriginClientMod.java` (button + cleanup hook)
- `hud/FrameStats.java`, `hud/HudElements.java`, `mixin/GameRendererFrameMixin.java`, `mods/Mods.java` (1.21.1 fork)
- `tools/packhub-tests/`
- `render/terrain/*` (OriginRender, TerrainCuller, TerrainShaders), `mixin/Sodium*`, `mixin/DebugScreenOriginRenderMixin.java`, `tools/origin-render-tests/`

## Next
CI compile, then Will's pass. If Origin Render measures a win, the next step is moving Sodium's CPU section list onto the GPU too. After that, port the hub to 1.20 (next in the working order). The pure classes copy as-is; only the screen and accessor need a per-era check.
