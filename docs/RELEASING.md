# Releasing Origin

How a build gets from `main` into players' launchers. The repo has two areas
(see the root `README.md`):

- **Build & test** — `src/` + `tools/` on the `main` branch. Every PR and
  experiment lands here. `main` always reflects **at least** the latest
  release — usually ahead of it.
- **Website** — `website/`, deployed to originclient.org.

Shipping is one deliberate act: pushing a `launcher-v*` tag.

## How to ship

From a green `main` (build-check passing):

```
git tag launcher-v1.0.N        # N = last release number + 1
git push origin launcher-v1.0.N
```

That tag triggers `.github/workflows/launcher-release.yml`, which:

1. Builds every LIVE Origin Client mod jar (Gradle; see
   `src/mods/VERSIONS.md` for live vs staged).
2. Publishes the launcher (win-x64, self-contained single file) with the mod
   jars bundled, assembly version stamped from the tag (`launcher-v1.0.21`
   → `1.0.21`).
3. **Asserts every expected bundled jar is actually in the publish output**
   (guards the csproj's silent `Exists()` conditions).
4. Builds the per-user `OriginLauncher-Setup.exe` (Inno Setup) and creates
   the GitHub release with both the setup.exe and
   `OriginLauncher-win-x64.zip`.

Within ~10 minutes (or instantly on their next Play click), every running
launcher sees the new release, lights the download badge, and — because
updates are mandatory — refuses to launch the game until the player clicks
it and restarts into the new build.

## Rules

- Tags must be strictly increasing (`1.0.22` after `1.0.21`); the updater
  compares versions numerically against `releases/latest`.
- Never change the `launcher-v` tag prefix or the asset names
  (`OriginLauncher-win-x64.zip`, `OriginLauncher-Setup.exe`) — installed
  launchers key on them.
- Never commit built artifacts to the repo — releases live on GitHub
  Releases, built from clean CI runners.
- Only tag commits that are on `main` and green in build-check.
- Rolling back = pushing a fix-forward release. The mandatory-update gate
  compares against the *latest* release, so publishing a new (fixed) release
  immediately supersedes a bad one.
- The repo (or at least its releases) must stay public — launchers poll the
  release feed unauthenticated.
