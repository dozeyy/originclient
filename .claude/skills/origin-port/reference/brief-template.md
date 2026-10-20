# Brief template: one mc-port-parity per era group

Fill in every <slot>. Paste the recipe rows for this group inline. The agent starts with zero history.

```
GOAL: Port <feature> from <source module> to <group modules> with 1.21.1 parity.
PROJECT: C:\Users\Will\Documents\Minecraft Mods\Origin Client (worktree; branch claude/<feature>-port-<group>
  from origin/main).

SOURCE: origin/<branch> @ <sha>, module <source>. Files: <list from git diff --stat>.
LATER REFERENCE FIXES (landed after the source commit; port these too):
  <sha> <title> ... (from `git log <sha>..origin/<branch> -- src/mods/versions/<source>` and `..origin/main`)
  If none: "none found as of <date>". Re-run that log yourself before you finish; anything new goes in too.

PARITY LIST (what must match 1.21.1's shots):
  - <screen/element>: <exact behaviour>
  ...
DO NOT PORT: <files/options that stay 1.21.1-only, with the reason>.

ERA FACTS FOR THIS GROUP (from .claude/skills/origin-port/recipes/<category>.md, rows for <group>):
  <paste the rows: hook per module, traps, what is proven vs likely>
DONOR ORDER: <e.g. 1.21.8 first, then copy to 1.21.6 (byte-identical trees)>.
ERA NOTES: grep src/mods/docs/PORTING-ERA-NOTES.md for "<heading>"; era map in
  .claude/skills/origin-port/reference/era-map.md.

CRITERIA:
  1. Every parity item is visible in this group's dev shots (set <design|world|style|...>, label <feature>-<group>).
  2. Zero `Mixin apply ... failed` in run.log, and every new @Inject proven to land (mixin export or a
     log line from the hook). defaultRequire is 0, so "zero failures" alone proves nothing.
  3. Fail-soft: every new draw/hook body catches Throwable, latches a health flag, and logs the first throwable.
  4. No per-frame allocation in draw paths (no new Gfx / GameProfile / list per frame).
  5. Shared code: shared/ + sync.py, or overrides.txt for a deliberate fork; `sync.py --check` passes.
CONSTRAINTS: never open Minecraft or launcher windows on Will's PC (CI only). No MEMORY.md edits.
  Signatures come from bytecode (javap on the loom jar / the loom-cache sources jar / the 26.x api dump /
  ref-189), never from memory or the donor. Mark anything not proven as "likely".
DONE WHEN: pushed to claude/<feature>-port-<group>; build-check green; dev-shots run <id> read (_sheet.jpg)
  and matching 1.21.1; report with a parity table (item x module: done / differs-why / dropped-why),
  the CI run ids, new era facts for the recipe, and a MEMORY ENTRY.
AUTHOR MODEL: opus (mc-port-parity); reviewers opus or higher.
```

## Per-group extras to add
- **G4 (1.21.6/1.21.8)**: port to 1.21.8 first, then diff the two trees. They were byte-identical, so check that's still so.
- **G5 (1.21.10/1.21.11)**: 1.21.10 = 1.21.11 minus the rename wave. Do 1.21.11 first, then backmap.
- **G6 (26.x)**:
  - Derive from 26.2, never from 1.21.11.
  - Each module needs `api-dump.txt` / `api-code.txt` in a dev-shots run before any hook is written.
  - Boot the 26.1 jar on its OLDEST version (26.1) once.
- **G7/G8 (Gfx)**:
  - Regenerate the heavily diverged files (OriginUi, OriginButtonRenderer, OriginScreenRenderer, ModIcons, OriginShaders,
    OriginText, OriginTheme, Mods.java) from 1.21.1 plus era edits. Don't hand-merge them.
  - Run the `javac --release N -proc:none` gate.
  - Grep for `debugQuads`, `isHovered`, `getX(`, `Button.builder`, `enableScissor`.
- **G9 (1.8.9)**:
  - Name lookups go through ref-189. List "LOOKUPS NEEDED" / "SRG NEEDED"; never guess an SRG name.
  - Use HEAD/RETURN hooks in OptiFine-replaced classes.
