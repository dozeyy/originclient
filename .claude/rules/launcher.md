---
paths:
  - "src/OriginLauncher.App/**"
  - "src/OriginLauncher.Tests/**"
  - "src/OriginSetup/**"
  - "src/OriginSetup.Tests/**"
  - "tools/release/**"
---
# Launcher-side detail (loads only when working on the launcher or installer)

Moved verbatim from CLAUDE.md on 2026-10-17 (Claude Lean).

## Launcher stack
- Launcher: C# / .NET 8, WPF. CmlLib.Core (Mojang manifest, downloads, Fabric
  install, launch args). MSAL for MSA→Xbox→XSTS→Minecraft auth. Windows DPAPI
  for token-at-rest. System.Text.Json for config. Settings writes go through
  `SettingsStore.Update(mutate)` ONLY — never write a whole cached snapshot.
