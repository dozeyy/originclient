---
name: origin-port
description: Ports an Origin Client feature from one version module (normally 1.21.1, the design truth) to other Minecraft versions using the proven per-era methods. It picks the feature's category recipe, splits the targets into era groups, writes one brief per group from a template, dispatches mc-port-parity per group in worktrees, runs the scoped CI loop, then review and verify.
when_to_use: Will or a plan says "port X to other versions", "bring X to every version", "carry this to 1.20 / 26.x / 1.8.9", "same on all versions", "propagate the fix", or a feature has landed on 1.21.1 and the next step is the other modules. Also use it when writing an mc-port-parity brief by hand.
argument-hint: "<feature> <source module> <target modules|all>"
---

# Origin port: reuse the proven method, don't re-derive it

Every Origin feature has been ported across about 20 modules several times: the redesign,
fix rounds, social, Friends, the clean title look, JEI, hitbox, ModIcons, baked masks.
The methods that worked are written down. This skill makes the next port start from them.

**Files (read on demand, not up front):**
- `reference/era-map.md`: every live module with its loader, Java, render backend, GUI traits,
  Sodium/Iris/JEI and donor. **Always read it.**
- `recipes/<category>.md`: per-era hooks, traps and proof for one kind of feature.
- `reference/brief-template.md`: the brief for each era group.
- `reference/verify-loop.md`: the scoped CI loop, the static checks, and what "done" means.
- Deep detail: `src/mods/docs/PORTING-ERA-NOTES.md` (grep the heading named in a recipe),
  `src/mods/VERSIONS.md`, `src/mods/versions/1.8.9/PORT-189.md`.

## Steps

1. **Pin the source.** Get the branch and commit holding the finished feature, plus the file list
   (`git diff --stat <base>..<src>` on the source module). Also find the reference
   fixes that landed after the feature: run `git log --oneline <src-commit>..origin/<src-branch> -- src/mods/versions/<src>`
   and `git log --oneline <src-commit>..origin/main -- src/mods/versions/<src>`.
   The brief's addendum lists every commit found this way. Proven 2026-10-19: the clean-look review addendum
   (`menu.playdemo`, lead/extra row counts) was missed by most ports because it landed after their brief.
2. **Pick the category** (one or more) and load its recipe:

   | The feature touches | Recipe |
   |---|---|
   | Title screen, pause, any Screen layout, buttons, widgets, menu backdrop/blur | `recipes/title-menus.md` |
   | Loading overlay, level-loading, progress/connect/message screens, reload | `recipes/loading-screens.md` |
   | HUD elements, chat, tab list, scoreboard, HUD editor | `recipes/hud.md` |
   | Hitboxes, block outline, chunk borders, waypoints, world lines (ThickLine) | `recipes/world-overlays.md` |
   | Sodium / Iris internals, shadow pass, culling, Video Settings, Color Saturation / post | `recipes/shaders-sodium-iris.md` |
   | Keybinds, toggle sprint/sneak, zoom, freelook, mouse/scroll | `recipes/input-keybinds.md` |
   | Mod-menu cards, options, config fields, ModIcons, OriginDeck | `recipes/modmenu-options-config.md` |
   | JEI or any other third-party mod hook | `recipes/jei-third-party.md` |
   | Account chip, skins, Friends, social, capes, cosmetics, nametags | `recipes/social-skins-cosmetics.md` |
   | Particles, entity rendering, item physics, weather, sky, time | `recipes/particles-entities.md` |
   | Anything on 1.8.9 | `recipes/legacy-forge-189.md` **plus** the feature's category |

3. **Split the targets into era groups** using the era-map's Group column. One group is one agent.
   Never put two backends in one group. The default groups:
   - **G1**: 1.21, 1.20.2, 1.20.4 (GuiGraphics, near-verbatim from 1.21.1)
   - **G2**: 1.20 (GuiGraphics, covers 1.20-1.20.1; buffer API, 3-arg scroll, no sprite atlas; from 1.21.1 + the 1.20.1 deltas)
   - **G3**: 1.21.4 (blit rework, batched GUI)
   - **G4**: 1.21.6, 1.21.8 (deferred GuiRenderState; the two trees are byte-identical)
   - **G5**: 1.21.10, 1.21.11 (extract/submit, 1.21.9+ input, 1.21.11 rename wave)
   - **G6**: 26.1, 26.2, 26.3 (extract GUI, Java 25, unobfuscated; derive from 26.2)
   - **G7**: 1.19.4, 1.19.3, 1.19.2, 1.18.2 (Gfx PoseStack wrapper)
   - **G8**: 1.17.1, 1.16.5 (Gfx; 1.16.5 is fixed-function and Java 8)
   - **G9**: 1.8.9 (Forge, MCP, Mixin 0.7.11, OptiFine)

   A small feature can merge G1+G2 or G7+G8. Never merge G9 with anything else.
4. **Write one brief per group** from `reference/brief-template.md`. Each brief carries:
   - the parity list;
   - the recipe's rows for that group, pasted in, not just linked;
   - the "later reference fixes" addendum from step 1;
   - the donor order;
   - the do-not-port list.
5. **Dispatch** one `mc-port-parity` per group:
   - Run them in parallel with `isolation: "worktree"`, one branch each, `claude/<feature>-port-<group>`.
   - Shared-code edits (`src/mods/shared/`) go in ONE unit before the fan-out, never inside parallel units.
6. **Verify per group** with `reference/verify-loop.md`:
   - The pre-push static gates come first.
   - Then one scoped CI run: `[shots:<modules>]` or `gh workflow run dev-shots -f modules=<list>`.
   - Never re-run CI that already passed on the same commit.
7. **Review**: code-reviewer ∥ verifier per group, each with the group's diff and its CI run ids.
   - Reviewers check the recipe's trap list, the per-frame-allocation rule, and parity against 1.21.1's shots.
   - A failure goes back to the same group's agent with the findings verbatim.
8. **Collect** each group's MEMORY ENTRY and every new per-era fact.
   - New facts go into the matching recipe in the same close-out commit, so the next port gets them.
   - The single closing `chore-runner` appends MEMORY.md.

## Rules every group inherits

- **1.21.1 is the design truth.** Match its shots. Never invent a new look on another version.
  Mandate 5 applies: the mechanism may differ per era, but the look may not.
- **Fail-soft:**
  - Mixin configs are `required:false` with `defaultRequire:0`, so a dead target is SILENT.
  - Every Origin draw or hook body catches Throwable and latches a health flag. The first catch logs the throwable.
  - A feature `@Inject` checks its own toggle.
- **Facts come from bytecode, not from memory or the donor:**
  - Fabric modules: javap the mapped jar, or the loom-cache sources jar.
  - 26.x: the PORTING AID `api-dump.txt` and `api-code.txt`.
  - 1.8.9: ref-189 lookups.
  - Re-derive the full call path of every INVOKE target on each version. Don't reuse the donor's signature.
- **No per-frame allocation** in draw paths, such as a new `Gfx`, a new `GameProfile`, or a list rebuilt every frame.
  It was the most common review Low in the 2026-10-19 port.
- **Shared vs fork:**
  - Edit `src/mods/shared/`, then run `python tools/shared-sync/sync.py`. CI runs `--check`.
  - Fork a shared file by listing it in that module's `overrides.txt`.
  - Pre-1.20 Gfx modules carry `.no-shared-sync`.
- Never open Minecraft or launcher windows on Will's PC. Proof comes from CI (Xvfb).
