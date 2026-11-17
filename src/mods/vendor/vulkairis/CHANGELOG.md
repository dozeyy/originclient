# Vulkairis Changelog

## 0.8.12+v21.0.4.0 — Phases 0–13 implementation baseline

Experimental prototype. Not generally compatible with Iris shader packs.

### Tested dependencies (pinned — do not mix versions)

- Minecraft 1.21.1, Fabric Loader ≥ 0.19.5, Java 21
- Fabric API 0.116.17+1.21.1
- Iris 1.8.14-beta.1+mc1.21.1
- VulkanMod 0.6.7+1.21.1

### Supported render stages

- Vertex/fragment compilation to SPIR-V (Vulkan 1.1 target) with validation and reflection
- Program link validation (stage interfaces, descriptors, sampler budget)
- Composite/final post-chain execution when bound (experimental — game-run proof pending)
- Controlled test draws behind an explicit toggle (experimental — game-run proof pending)

### Known limitations

- No terrain/entity/shadow/water stage execution; packs needing them are refused with reasons
- No tessellation/geometry/compute execution; no full semantic SPIR-V validation
- GUI/HUD rendering is never intercepted (by design)
- Depth copy/resolve has no GPU implementation yet (loudly diagnosed, first triage step)
- Performance numbers are not published: no identical-scene benchmark has been captured

### Diagnostics

- `/vulkairis status` — bridge state, device, last compile/link errors, caches, fallback guidance
- `/vulkairis shader` — active pack and fallback reason
- `/vulkairis compatibility` — static plus observed run/fell-back/rejected per family
- `/vulkairis targets` — target dimensions, formats, layouts, backing and clear state
- `/vulkairis architecture` — subsystem report
- Opt-in dumps (`dumpTranslatedSource`, `dumpSpirv`, `dumpReflection`) write to
  `run/vulkairis-dumps/`; every failure carries a stable `VULK-N` diagnostic ID
