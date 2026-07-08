# Releasing Origin

This folder is the release channel's home: how a build gets from `main`
(build/test) into players' launchers. The repo is split into three areas —
see the root `README.md`:

- **Build & test** — `src/` + `tools/` on the `main` branch. Every PR and
  experiment lands here. Nothing that merges to `main` reaches players.
- **Release** — the **`release` branch** + this folder. Pushing to `release`
  is the one and only action that ships.
- **Website** — `website/`, deployed to originclient.org by its own
  workflow on pushes to `main`.

## How to ship

```
git checkout release
git merge main          # take exactly what you've tested on main
git push origin release
```

That push triggers `.github/workflows/launcher-release.yml`, which:

1. Builds the Origin Client mod jar (Gradle, Java 21).
2. Publishes the launcher (win-x64, self-contained single file) with the mod
   jar bundled, assembly version stamped `1.0.<run#>`.
3. Creates GitHub release `launcher-v1.0.<run#>` with
   `OriginLauncher-win-x64.zip`.

Within ~10 minutes (or instantly on their next Play click), every running
launcher sees the new release, lights the download badge, and — because
updates are mandatory — refuses to launch the game until the player clicks
it and restarts into the new build.

## Rules

- Never commit built artifacts to this folder (or anywhere in the repo) —
  releases live on GitHub Releases, built from clean CI runners.
- Don't push directly to `release` with work that hasn't been on `main`
  first; the channel exists precisely so main can be messy and release
  can't.
- Rolling back = pushing a fix-forward release. The mandatory-update gate
  compares against the *latest* release, so publishing a new (fixed) release
  immediately supersedes a bad one.
