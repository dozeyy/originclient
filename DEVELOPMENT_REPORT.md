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

## Origin Render (GPU-driven terrain): not prototyped. Blocker:
- Nvidium, the only working GPU-driven Sodium backend, is NVIDIA-only (mesh shaders), LGPL, and turns itself off whenever Iris shaders are on. That conflicts with mandate 3.
- Voxy is All-Rights-Reserved and needs Sodium 0.8, which is already the opt-in `-Pvoxy` variant.
- Stock 1.21.1 ships Sodium 0.6.13 to keep Iris 1.8.8 stable. A custom backend would mean forking Sodium's renderer per version.
- This is not feasible behind a safe, isolated default-off flag within this scope.
- Suggested path instead: keep `-Pvoxy` for far terrain, and measure before any renderer work.

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
- **Not compiled against Minecraft**: Fabric/Mojang maven is 403 in the sandbox. Pushing the branch lets build-check CI compile it.
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

## Main files
- `src/mods/versions/1.21.1/src/client/java/com/origin/client/client/packs/*`
- `mixin/PackSelectionScreenAccessor.java` + `originclient.client.mixins.json`
- `OriginClientMod.java` (button + cleanup hook)
- `hud/FrameStats.java`, `hud/HudElements.java`, `mixin/GameRendererFrameMixin.java`, `mods/Mods.java` (1.21.1 fork)
- `tools/packhub-tests/`

## Next
CI compile, then Will's pass. After that, port the hub to 1.20 (next in the working order). The pure classes copy as-is; only the screen and accessor need a per-era check.
