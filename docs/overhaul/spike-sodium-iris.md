# Spike: can an Origin module run on a Sodium/Iris build it does not bundle?

Date 2026-10-25. Branch `claude/spike-sodium-pin` (from origin/main de6b3eb6a). Spike only, not the feature.
Question behind Phase 5 (audit/architecture.md "riskiest assumption"): can an imported modpack that pins an
older Sodium/Iris keep it, with Origin's own mixins into Sodium/Iris still working?

## Answer in four lines

1. Yes for the 1.21.11 and 1.21.1 lines, inside a bounded window. One unmodified Origin jar booted clean
   (zero mixin-apply failures, world joined, Iris shadow pass on, 32 screenshots) on five Sodium/Iris pairs on
   1.21.11 and two older pairs on 1.21.1. Origin's Smart Terrain stays off on those (exact-pair gate).
2. Not for every pair. 1.21.1 with Sodium 0.8.13 + Iris 1.8.14-beta.1 boots but 4 of 13 Origin Sodium mixins fail
   to apply (logged WARN, non-fatal). Sodium and Iris must move as a pair: Sodium 0.6.5 with the bundled Iris
   1.8.8 crashes in Iris (NoClassDefFoundError), and Fabric Loader does not refuse that combination.
3. The nested-jar worry is settled: on 1.21.1 a top-level Sodium/Iris in the mods folder beats the copy nested in
   `originclient-1.21.1.jar`. No "no-nested" build is needed.
4. Recommendation: A1 (per-profile pinned component set) delivering a Sodium+Iris PAIR as top-level jars, plus a
   short CI-proven list of allowed pairs per module. No per-configuration Origin jars.

## 1. Where Origin touches Sodium/Iris (static map)

Every module's Sodium/Iris mixin config is `"required": false` with `"defaultRequire": 0`, so an injector whose
target changed is skipped, not fatal. Hard spots are accessor mixins (a cast at the call site throws if the
accessor did not apply) and anything that holds Sodium types in a class that loads without Sodium.

| Module (shipped pair) | Sodium/Iris mixin files | Guarding |
|---|---|---|
| 1.21.1 (Sodium 0.6.13, Iris 1.8.8) | 13: IrisPipelineHalfRate, IrisShadowDirectives (@Pseudo, require 0), IrisShadowRendererHalfRate, SodiumChunkRenderer (require 0), SodiumGraphReuse, SodiumOcclusionStamp, SodiumViewportShadowMap, SodiumWorldRendererReuse, 5 accessors | All soft by config. Smart Terrain gated by `SmartTerrainUpdates.VERSIONS_OK` (exact 0.6.13 + 1.8.8). OffMapShadows is dev-only. |
| 1.21.11 (Sodium 0.8.12, Iris 1.10.7) | 10: same families plus SodiumWalkEnd, 2 accessors | All soft by config. `render/SodiumIrisBuilds`: SODIUM and exact-pair SMART_TERRAIN. Iris reached via MethodHandles (HalfRateShadows, IrisBridge). |
| 1.20 / 1.20.1 (Sodium 0.5.13, Iris 1.7.6; 1.20: 0.4.10 + 1.6.4) | 17 files | `OriginMixinPlugin.shouldApplyMixin` + `SodiumIrisBuilds` select the SODIUM_05 / SODIUM_04 / IRIS_17 / IRIS_16 mixin sets by exact version. The only module already doing per-build gating. |

## 2. Evidence: real boots

Harness (historical spike run; today this runs only in CI, never `runClient` on Will's PC): `gradlew runClient` (dev) or `prodClient` (built jar, production namespace), dev-shots world flow
(`ORIGIN_SHOTS_SET=world`), the CI shader pack with shadows on, `-Dmixin.debug.verbose=true`,
`tools/ci/perf_log.py`. "applied" below means a `Mixing X into Y` line AND zero `Mixin apply ... failed` lines;
perf_log's count alone is not enough (see top-new). Scripts in `docs/overhaul/spike-tools/`.

| MC / Origin module | Sodium | Iris | Run | Result |
|---|---|---|---|---|
| 1.21.11 | 0.8.12 (shipped) | 1.10.7 | dev | 10/10 applied, 0 failures, 32 shots, Smart Terrain ON |
| 1.21.11 | 0.8.0 | 1.10.0 | dev | 10/10, 0 failures, 32 shots, Smart Terrain inert |
| 1.21.11 | 0.8.4 | 1.10.5 | dev | 10/10, 0 failures, 32 shots, inert |
| 1.21.11 | 0.8.14 (newer) | 1.10.8 | dev | 10/10, 0 failures, 32 shots, inert |
| 1.21.11 | 0.8.15-beta.1 (newest) | 1.10.8 | dev | 10/10, 0 failures, 32 shots, inert |
| 1.21.1 | 0.6.13 (shipped) | 1.8.8 | dev | 13/13, 0 failures, 32 shots, Smart Terrain ON |
| 1.21.1 | 0.6.0 | 1.8.0 | dev | 13/13, 0 failures, 32 shots, inert; one injector silently absent |
| 1.21.1 | 0.6.5 | 1.8.1 | dev | 13/13, 0 failures, 32 shots, inert |
| 1.21.1 | 0.6.5 + 1.8.1 top-level over nested 0.6.13 + 1.8.8 | | prod | loader list shows only sodium 0.6.5 and iris 1.8.1; 13/13, 0 failures, 32 shots |
| 1.21.1 | 0.8.13 + Iris 1.8.14-beta.1 top-level | | prod | boots, 32 shots, but 4 Origin mixins FAIL (below) |
| 1.21.1 | 0.8.13 | 1.8.14-beta.1 | dev | CRASH inside Iris's own mixin (below); not an Origin mixin |
| 1.21.1 | 0.6.5 top-level, Iris 1.8.8 nested (mismatched pair) | | prod | CRASH at "Rendering overlay" |

Screenshots checked by eye: contact sheets of 1.21.1 old-0.6.0 and prod top-new show the Origin HUD (compass,
hint panel) and cosmetics menu in-world. Shader look on the alternates was NOT compared against the baseline.

Loader prefers the top-level jar (1.21.1 prod, nested copy is 0.6.13, higher than the top-level one):

```
Loading 75 mods:
	- fabric-api 0.116.13+1.21.1
	- iris 1.8.1+mc1.21.1
	- originclient 0.4.2
	- sodium 0.6.5+mc1.21.1
```

Sodium 0.8.13 + Iris 1.8.14-beta.1 on 1.21.1 (prod, game continues, features degrade):

```
Mixin apply for mod originclient failed originclient.client.mixins.json:SodiumGraphReuseMixin ... InvalidInjectionException Invalid descriptor
Mixin apply for mod originclient failed ...:SodiumDefaultChunkRendererAccessor ... InvalidAccessorException No candidates were found
Mixin apply for mod originclient failed ...:SodiumChunkRendererMixin ... InvalidInjectionException Invalid descriptor
Mixin apply for mod originclient failed ...:SodiumViewportShadowMapMixin ... @ModifyReturnValue return value modi...
[smart-terrain] hooks seen: sectionAdded=NO sectionRemoved=NO ... (versions not matched: feature inert)
```

Mismatched pair (not an Origin fault; Iris 1.8.8 needs Sodium 0.6.13 classes and Fabric did not stop the load):

```
java.lang.NoClassDefFoundError: net/caffeinemc/mods/sodium/client/gl/shader/uniform/GlUniformFloat2v
	at knot/net.irisshaders.iris.pipeline.programs.SodiumShader.<init>(SodiumShader.java:69) [iris-1.8.8+mc1.21.1]
	at ...SodiumPrograms.lambda$buildProgram$2(SodiumPrograms.java:178)
	at ...GlProgram.<init>(GlProgram.java:29) [sodium-fabric-0.6.5+mc1.21.1.jar]
```

Dev-only crash with Iris 1.8.14-beta.1 (Iris's own `mixins.iris.json:MixinLevelRenderer`, `@ModifyArg ...
desc is null`) did not occur in the prod run of the same pair, so it is a dev-environment artefact; cause not
proven.

### Static signature check (cheap pre-gate)

`docs/overhaul/spike-tools/sigcheck.py` reads every Origin Sodium/Iris injector, accessor and @Shadow from a
module and checks the target method, field and `@At` descriptor exist in a given jar pair (javap). It matched
every boot result:

| Module / pair | Resolves | Missing |
|---|---|---|
| 1.21.11, five pairs 0.8.0-0.8.15b1 | 32/32 each | none |
| 1.21.1 0.6.13 + 1.8.8 | 46/46 | none |
| 1.21.1 0.6.5 + 1.8.1 | 46/46 | none |
| 1.21.1 0.6.0 + 1.8.0 | 45/46 | OcclusionCuller.addNearbySections (boot: silently skipped) |
| 1.21.1 0.8.13 + 1.8.14b1 | 44/46 | setPendingUpdate no longer inside scheduleRebuild/scheduleSort |
| 1.20.1 module: 0.5.13/1.7.6, 0.5.11/1.7.5, 0.5.8/1.7.2 | 44/54 identical on all three | only the IRIS_16/SODIUM_04 sets (gated off on 1.20.1) plus a checker blind spot for `<init>` injects |

The 1.20.1 result is static only: no 1.20.1 boot was run.

## 3. Approaches compared

| | (a) Origin mixins fully soft, one jar | (b) one Origin jar per Sodium/Iris configuration | (c) per-profile pinned component set (A1) | (d) refuse/ignore pins outside a tested list |
|---|---|---|---|---|
| Reliability | Already true for crashes (config soft). Not true for correctness: failed injectors silently disable features, and mismatched pairs crash in Iris/Sodium themselves | Highest per config, each jar compiled against its pair | Delivery only; safe if the pair is valid | Safest; loses the modpack's pin |
| Build/CI cost | Low; add an allowed-pair matrix | High: N jars per version, reuse logic in plan.py, bigger releases (the 1.21.1 "voxy variant" is this model) | Low if jars come from the existing component store and catalog SHA pins | None |
| User-visible complexity | None | None if automatic, but a Play-time download per configuration | None: pick profile, Play | A silent downgrade the user may notice |
| Fits "one profile, several configurations, picked automatically" | Needed underneath | Possible but heavy | Yes, this is it | Fallback |

(e) Rebuild only Fabric API + user mods per profile and never touch Sodium/Iris: simplest, but it is the case
this spike was meant to avoid; the evidence says it is not required.

## 4. Recommendation

Use (c) with (a) as its safety floor and (d) as the out-of-list behaviour.

1. Keep one Origin jar per MC version. Do not build per-configuration jars; reserve (b) for a pair whose mixins
   fail (the 1.21.1 + Sodium 0.8.x line, which already has its voxy variant).
2. A profile pins Sodium and Iris as ONE unit (a pair id), materialised as top-level jars in the launch folder.
   Proven: top-level beats nested on 1.21.1, so the bundled stack stays as the default and a pin just overrides it.
   Never pin one without the other; Fabric will not stop the mismatch.
3. Each module carries an allowed-pair list (extend `SodiumIrisBuilds`: today 1.20 has it, 1.21.11 has two flags,
   1.21.1 has an exact-version check inside Smart Terrain only). Allowed means CI-booted with zero
   `Mixin apply ... failed` lines. Starting list from this spike: 1.21.11 Sodium 0.8.0 to 0.8.15-beta.1 with Iris
   1.10.0 to 1.10.8 (five pairs booted; intermediate pairs untested); 1.21.1 Sodium 0.6.5 + Iris 1.8.1 and
   0.6.0 + 1.8.0 (the latter loses one injector).
4. Outside the list: use Origin's own pair and tell the user in Fix Compatibility ("this pack's Sodium was
   replaced"), one click. Do not run an unverified pair.
5. CI: a matrix job reusing `run-dev.sh`/`run-prod.sh` (about 4 minutes per pair on a local machine, build
   included) that fails on `Mixin apply ... failed`, with `sigcheck.py` as a seconds-long pre-gate for new
   Sodium/Iris releases. Gate every feature that depends on one injector (Smart Terrain already is) on the pair.

## 5. Limits, not proven here

- Only 1.21.11 and 1.21.1 were booted; 1.20.1 and every other module are static-only or untested.
- Intermediate versions between the booted ones are inferred from signatures, not booted.
- Whether shadows, half-rate shadows and chunk-render tweaks look and perform the same on alternates: not
  measured (harness counters for half-rate read kept=0 on the baseline too).
- Smart Terrain was exercised only on the exact pair; it is inert elsewhere by design.
- Iris 1.8.14-beta.1 production run only reached the world and screenshots; shader correctness not examined.
- Dev-environment crash of Iris beta: cause unknown.
- Spike patch `src/mods/versions/1.21.1/build.gradle` (`-PaltSodium/-PaltIris/-Pglsl`) is spike-only; do not merge.
- No launcher code, component store, or Modrinth lookup was touched or tested.
