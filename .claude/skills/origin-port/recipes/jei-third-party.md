# Recipe: JEI and other third-party mod hooks

## Rules that hold everywhere
- Soft dependency: compile against the mod and guard every hook. JEI hooks use
  `IMixinConfigPlugin` + `FabricLoader.isModLoaded`, or the module's self-check.
  - The JEI mixin self-check and foreign-JEI detection exist on 1.21.1.
- `isOverlayEnabled` is a trap. The two real choke points are in the memory note "jei-toggle-architecture".
  JEI also mutates vanilla's potion layout.
- Pin the version from live metadata (Modrinth version id). A nested jar can carry its own exact Minecraft pin:
  JEI 29.40+ nests `mezz_config`, which requires 26.1.2 exactly. Boot a multi-version jar on its OLDEST version.
- Fork `Mods.java` / `ModOption.java` in `overrides.txt` for JEI modules.

## JEI per module

| Module | JEI | Draw param | Render hooks | Other deltas |
|---|---|---|---|---|
| 1.18.2 | 10 | PoseStack | onDrawScreenPost / onDrawForeground | `common.*` packages, boolean charPost, stub JeiSettings |
| 1.19.2 | 11 | PoseStack | same | — |
| 1.19.3 | 12 | PoseStack | same | — |
| 1.19.4 | 13 | PoseStack | same | — |
| 1.20 | 14.0.0.11 | GuiGraphics | same | 3-arg scroll; `String getName/getDescription` |
| 1.20.2 | 16.0.0.2 | GuiGraphics | same | 4-arg scroll (JEI 16+) |
| 1.20.4 | 17.3.1.5 | GuiGraphics | same | — |
| 1.21 / 1.21.1 | 19.27 | GuiGraphics | same | — |
| 1.21.10 | 26.3.0.31 | GuiGraphics | renamed drawForScreen / drawForContainerScreen | char input via CharacterEvent |
| 1.21.11 | 27.17.0.50 | GuiGraphics | same as 1.21.10 (one mixin pair serves both) | same |
| 26.1 | 29.37.0.100 | GuiGraphicsExtractor | likely unsplit drawForScreen (the split arrived in JEI 30); UserInput is `mezz.jei.gui.input` | pinned below 29.40 (mezz_config) |
| 26.2 | 30.42 | GuiGraphicsExtractor | drawForScreenBackground(Screen, GGE) + drawForScreenForeground(Screen, GGE, I, I) | UserInput moved to `mezz.jei.common.input`; needs Fabric API ≥ 0.155.0 |
| 26.3 | 31.12.0.65 | GuiGraphicsExtractor | likely as 26.2 (check the module) | needs Fabric API ≥ 0.156.3 |
| 1.16.5, 1.17.1, 1.21.2–1.21.8 | **no Fabric JEI exists** | — | — | strip JEI rows (`gfx_old.strip_jei_*` approach) |

The JEI 10–19 hook names come from the memory map "jei-coverage-complete". Before you rely on one, read
that module's JEI mixin (grep `mezz.jei` under `src/mods/versions/<m>`).

## Other third-party integration seen in Origin
- **TACZ** (ammo and gun HUD): empty creative tabs aren't an Origin bug. The TACZ gun index fills only on a local datapack reload.
- **Iris / Sodium**: see `shaders-sodium-iris.md`.
- **ModMenu**: shipped by the catalog. Origin's own menu stays primary.
- Reflection into another mod's classes uses cached reflection, and a miss means "feature off".
  Never trust a mod's declared range when you mix into its internals (category rule).

## Proof
- `dev-shots set=design` with JEI present: open an inventory shot.
- Prove the JEI self-check line in run.log.
- Run once without JEI (soft-dep path) when the hook is new.
