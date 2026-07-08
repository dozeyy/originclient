# Origin

A premium Windows Minecraft launcher (Origin Launcher) + custom client mod
(Origin Client), with the marketing site alongside. One repo, three areas:

| Area | Where | Ships via |
|------|-------|-----------|
| **Build & test** | `src/` (launcher + mod) and `tools/` (asset generators), on `main` | never ships directly |
| **Release** | the `release` branch (process + docs in `release/`) | push to `release` → CI builds and publishes `launcher-v*` GitHub releases → running launchers self-update |
| **Website** | `website/` | pushes to `main` deploy to originclient.org |

- `src/OriginLauncher.App/` — WPF launcher (.NET 8): accounts, versions,
  loaders, one-click provisioning, self-updates.
- `src/OriginClient.Mod/` — the in-game Fabric mod (MC 1.21.1): Origin's
  menus, loading screens, and QoL features. `VERSIONS.md` there covers the
  multi-version/multi-loader strategy.
- `release/RELEASING.md` — how shipping works and the rules of the channel.

Day-to-day development happens on feature branches merged into `main`.
Nothing reaches players until it's deliberately pushed to `release`.
