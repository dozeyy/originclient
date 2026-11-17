# sodium-compat (Origin)

Iris 1.8.14-beta.1 is built against Sodium 0.8 and links to some of Sodium's classes even when no Sodium
renderer runs (its crash-report hook, vertex serializer registration, sprite tracking, a few shader
interfaces). Under VulkanMod, Sodium is not loaded, so those classes must come from somewhere.

This module is Origin's own minimal stand-in for exactly those types:

- Written by Origin from the call sites in Iris 1.8.14-beta.1 (bytecode references) and VulkaIris.
  No Sodium source or binaries were copied. Sodium is PolyForm Shield; nothing of it is redistributed.
- Only the class names, member names and descriptors match, because the JVM links by those names.
  The behaviour is the smallest that keeps Iris working: small value helpers do the obvious maths,
  registries are plain maps, and the OpenGL-only types throw `NoGlUnderVulkan` if anything ever
  tries to use them (nothing does under VulkanMod).
- Deliberately absent: every Sodium class an Iris mixin targets (for example `RenderSectionManager`,
  `DefaultChunkRenderer`, `ChunkVertexEncoder$Vertex`). With no target, those mixins cannot apply, and
  VulkaIris removes their config before Mixin reads it.
- Mod id `vulkairis_sodium_compat`, version 1.0.0, client only. It does not claim to be Sodium. The
  separate `sodium-provider` marker does that, so a launcher can drop the claim without losing the
  classes.

Proof: `SodiumCompatCoverageTest` (named namespace, runs in `gradlew test`) walks every Iris class
and checks every Sodium class, field, method and constructor it references against this module.

License: LGPL-3.0-only, like the rest of Origin's VulkaIris fork.
