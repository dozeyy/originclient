# Perf/shader catalog generator

`gen_catalog.py` produces `PerformanceModCatalog.Data.cs` entries from the **live
Modrinth API**, with exact version pins + direct download URLs (never "latest" —
mismatched Sodium/Indium pairs break the game).

**Run it on a machine with network access to `api.modrinth.com`.** The Origin dev
sandbox is firewalled off from Modrinth by org egress policy, so this can't run
there or in CI — it's a deliberate local step.

## Fill the shaders for the rest of the 1.21 line

```
python3 tools/perf-catalog/gen_catalog.py \
    1.21.2 1.21.3 1.21.4 1.21.5 1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11
```

- Each line printed to **stdout** is a ready-to-paste C# entry. Replace the
  existing line for that version in
  `src/OriginLauncher.App/Core/Loaders/PerformanceModCatalog.Data.cs` (keep the
  file newest-first).
- **stderr** notes any version that stays `Partial` because Modrinth has no
  Sodium and/or Iris build for it yet — that version genuinely can't ship shaders
  until those exist upstream, and `HasShaderStack` keeps it hidden.

## Then it goes live automatically

A version flips to shader-capable the moment its line has both Sodium and Iris
(`PerfStackTier.Full`). To also give it the **Origin menus**, uncomment its line
in `VersionManager.OriginBuilds` (the whole 1.21.2–1.21.11 family is pre-written
there, commented) — it already maps to the shared family jar. Then ship from the
`release` branch as usual.
