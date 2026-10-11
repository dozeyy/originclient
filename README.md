# Origin

A premium Windows Minecraft launcher (Origin Launcher) + custom client mod
(Origin Client), with the marketing site alongside. One repo, two areas:

| Area | Where | Ships via |
|------|-------|-----------|
| **Build & test** | `src/` (launcher + mods) and `tools/` (asset generators), on `main` | pushing a `launcher-v*` tag → CI builds and publishes a GitHub release → running launchers self-update |
| **Website** | `website/` | pushes to `main` deploy to originclient.org |

- `src/OriginLauncher.App/` — WPF launcher (.NET 8): accounts, versions,
  one-click Fabric provisioning, self-updates.
- `src/OriginSetup/` — the custom installer + uninstaller
  (`OriginLauncher-Setup.exe`), in the launcher's look.
- `src/mods/` — the in-game Fabric mod, one standalone build per Minecraft
  API family: `shared/` (the canonical core every version copies from),
  `versions/` (shipped builds), `staged/` (work-in-progress builds that
  never ship). `src/mods/VERSIONS.md` is the live/staged registry.
- `tools/` — asset generators, `shared-sync/` (copies the shared mod core
  into every version), `release/` (release planning + notes) and `github/`
  (the label set).
- `docs/RELEASING.md` — how shipping works, the CI pipeline, release notes,
  labels, and the rules of the channel.

Day-to-day development happens on feature branches merged into `main`.
`main` always reflects at least the latest release; nothing reaches players
until a `launcher-v*` tag is deliberately pushed.
