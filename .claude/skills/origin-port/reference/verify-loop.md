# Verify loop for a port (scoped, CI-only)

Never open Minecraft or the launcher on Will's PC. All proof comes from CI (Xvfb), and every run is scoped to the touched modules.
A run that already passed on the same commit is done. Don't run it again.

## 1. Before pushing (local, seconds)
- **Java level gate:** `javac --release <N> -proc:none -d <scratch> <changed .java files>`.
  - With no classpath, javac still flags these as real errors:
    - language features above N;
    - `var` below 10;
    - JDK API misses whose location is a JDK type (List.of, isBlank, readAllBytes, Files.readString, Stream.toList).
  - Ignore cannot-find-symbol on MC/Fabric/Origin classes.
  - N is 8 for 1.16.5 and 1.8.9, 16 for 1.17.1, 17 for 1.18.2–1.20.4, 21 for 1.21.x, 25 for 26.x.
- **Parse gate:** look for "class, interface, enum, or record expected" or "unnamed classes are a preview feature".
  Both mean a stray `}` from slicing a method body with `index('\t}\n')`, which also matches a nested `\t\t}`.
  Search for `'\n\t}\n'` instead. Proven twice: build-check 135, and the 1.17.1 port.
- **Shared drift:** `python tools/shared-sync/sync.py --check`.
- **Import check after a clean merge:** a 1.21.1 hunk can bring in a newer API, e.g. `RenderType.debugQuads()`
  (1.19.4+) inside BlockOverlayRenderer. Grep the module for the era's missing names.
- **Dead-mixin audit (Fabric):**
  - `find src/mods/versions/<m>/.grad*/loom-cache/minecraftMaven -name "*sources.jar"`, then grep every `method =` name
    and INVOKE target in the new mixins against it.
  - In this repo's Bash tool write `.grad*`: a literal `.gradle` breaks the quoting.
  - 1.21.11 shipped 3 dead mixins this way: EntityNametagMixin, BlockEntityRenderDispatcherMixin, ParticleEngineMixin.
- **Mixin listed twice:** Mixin 0.8.7 silently skips a mixin that appears twice in one config. Grep the mixins.json. (proven 2026-10-19: a reviewer disassembled Mixin 0.8.7 `MixinConfig.prepareMixins`, which skips names already in `globalMixinList`).

## 2. CI (push the group branch)
- **build-check** runs on every push: compile, `sync.py --check`, launcher tests.
- **dev-shots** for the group:
  - Run it with a `[shots:<m1>,<m2>]` tag in the commit title. These must be module names from live-modules.json.
  - Or run `gh workflow run dev-shots -f modules=<m1>,<m2> -f set=<set> -f label=<feature>-<group> --ref <branch>`.
  - Sets:
    - `design`: Origin screens.
    - `world`: inside a fresh world, pause menu.
    - `style` with `style=buttons|menu|vanilla`.
    - `handoff` with `framelog=true` (and `video=true` for loading.mp4).
    - `perf`: Sodium/Iris screens. `perf_mods` defaults to true and loads the pinned real jars.
    - `shaderpack=true` with `set=world` selects a shadow-pass pack. It proves IrisShadowDirectivesMixin applies.
  - Output: PNGs on the `claude/dev-shots` branch, folder `<module>[-world]-<label>/`.
  - Read `_sheet.jpg` first, then single shots. `tools/ci/check_shots.py` fails a job that captured fewer PNGs than planned.
- **boot-smoke**: `gh workflow run boot-smoke -f modules=<list> --ref <branch>` (workflow_dispatch only). Use it for boot-only changes.
- **In-world step (per module, data-driven):** a module with `"inworld": true` in `src/mods/live-modules.json` gets it in
  both boot-smoke (input `inworld`, default true) and the dev-shots design set. After the title: a flat test world made
  straight through `WorldOpenFlows.createFreshLevel` (reopened if it exists), wait until Origin's HUD has drawn 60 times
  (`HudElements.passes`), shoot `inworld_hud_<w>x<h>.png`, open the Mod Menu over the world, shoot
  `inworld_modmenu_<w>x<h>.png`, Save and Quit to the title. Verdict line `[origin-smoke] in-world: PASS` or
  `... FAIL <reason>`; smoke.py and check_shots.py fail on FAIL, a crash, a missing shot or the deadline.
  boot-smoke uploads the shots as artifact `inworld-<module>`; dev-shots puts them in the module's folder (`_sheet.jpg`).
  - **Turning a module on:** port `OriginDevWorld.inWorldTick` + `inWorldEnd`, the `HudElements.passes` counter, the
    `ORIGIN_SMOKE_WORLD` call in `OriginClientMod.onEndTick` and the `ORIGIN_INWORLD` hand-off in `OriginDevShots.finish`
    (all 1.21.1), then set the flag. `test_ci_gates.py` fails a flagged module whose OriginDevWorld lacks the step.
  - Per-era: the world-creation call (`WorldOpenFlows` / `createFreshLevel` arity and `WorldPresets.FLAT` lookup) and
    the Save and Quit sequence differ by era; re-derive them with javap, never copy 1.21.1's.
- **ref-189**: 1.8.9 API lookups (class / member / find / srg) and a compile that prints only the errors.
- **Watching:**
  - `gh run watch` exits 0 whatever the result. Read `gh run view <id> --json conclusion`.
  - Poll CI at most every 3 minutes.

## 3. What proves a hook applied
- Zero `Mixin apply ... failed` lines is necessary but NOT sufficient: `defaultRequire:0` makes a missed target silent.
- Prove each new injection one of three ways:
  - **(a)** a dev-shot that shows its effect;
  - **(b)** `-Pmixinexport` (`run/.mixin.out`), then javap the target class for the `handler$...` method;
  - **(c)** a one-time log line from inside the hook.
- 26.x: `api-code.txt` publishes `javap -c` of the named classes. Check that the INVOKE target is really
  called inside the targeted method.
- 1.8.9: check the srg refmap in the built jar. Dev runs are MCP-named, but players run SRG.
- 26.3 pipelines: grep run.log for "Couldn't find source". Pipelines built under the loading overlay latch off.

## 4. Review and verify (separate agents)
- **code-reviewer** gets the group diff, the recipe's trap list, and this checklist:
  - fail-soft catch + latch + first log;
  - no per-frame allocation;
  - toggle self-guard on each @Inject;
  - shared vs overrides;
  - comments say target / why / what breaks.
- **verifier** gets the CI run ids. It reads the shots against 1.21.1's shots for the same set and fills the parity table.
- Only what Will can see stays UNVERIFIED: feel, real hardware, OptiFine on 1.8.9, real shader packs.
  Name each one explicitly in the report.
