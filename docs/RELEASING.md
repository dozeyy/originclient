# Releasing Origin

How a build gets from `main` into players' launchers. The repo has two areas
(see the root `README.md`):

- **Build & test** — `src/` + `tools/` on the `main` branch. Every PR and
  experiment lands here. `main` always reflects **at least** the latest
  release — usually ahead of it.
- **Website** — `website/`, deployed to originclient.org.

Shipping is one deliberate act: pushing a `launcher-v*` tag.

## How to ship

From a green `main` (build-check passing), in PowerShell from the repo root:

```
git checkout main
git pull
git tag launcher-v1.0.N        # N = last release number + 1
git push origin launcher-v1.0.N
```

That tag triggers `.github/workflows/launcher-release.yml` (about 5-6 minutes
end to end):

1. **Plan release** (Linux, ~30 s) — `tools/release/plan.py` diffs against
   the previous *published* release and decides which Minecraft versions
   need a fresh build. Unchanged ones have their jar copied straight out of
   that release's zip. It also writes the release notes
   (`tools/release/notes.py`). The run's Summary tab shows the plan as a
   table: each Minecraft version, built or reused, and why.
2. **Mods** (Linux, ~1-2 min) — `build-mods.yml` builds every changed
   version **at the same time**, one job each ("Mods / Minecraft 1.21.1").
3. **Build launcher + publish release** (Windows, ~3 min) — bundles all 16
   jars into the launcher (`.github/actions/publish-launcher`), **asserts
   every live jar is really in it**, builds the `OriginLauncher-Setup.exe`
   installer, and publishes the GitHub release with both the setup.exe and
   `OriginLauncher-win-x64.zip`, titled "Origin Launcher 1.0.N".

Before this pipeline (≤ v1.0.39) the same work ran one version after
another on a single Windows machine and took 12-40 minutes.

Within ~2 minutes (or instantly on window focus), every running launcher
sees the new release and lights the download badge. Updates are mandatory:
Play refuses until the player clicks the badge, which shows the download as
a full-screen progress bar and restarts into the new build.

## Rehearse first (optional, publishes nothing)

Actions → **launcher-release** → Run workflow. It runs every step above
against the latest published release — plan, reuse, parallel builds, notes,
installer, zip — but skips publishing. The setup.exe, zip and the notes it
would have posted are attached to the run as a "rehearsal-release" download
(an unstamped dev build, so installing it never triggers or blocks updates).

## Release notes

Written automatically from the commit titles since the previous release:

- **What's new** / **Fixes** / **Behind the scenes** — grouped by the title's
  prefix (`feat:`, `fix:`, `docs:`, `ci:`, `MEMORY:` …). A scope prefix like
  `1.21.11:` or `Tab Editor:` is kept so the line says what it's about.
- **Minecraft versions updated** — the shipped versions whose mod code
  changed.
- Install/update instructions and a link to the full diff.

So: **write commit titles for players**. "Fix: sprint toggle forgets its
state after a server hop" becomes a good release-note line;
"misc fixes" does not.

To rewrite the notes of every past release (only needed if the format in
`notes.py` changes): Actions → **relabel-releases** → Run workflow (tick
"dry run" first to preview in the log). Safe — the launcher's updater never
reads release titles or notes.

## Labels

`tools/github/labels.py` is the label set (type, area, status, priority,
plus one `mc <version>` label per live Minecraft build, generated from
`src/mods/live-modules.json`). The **labels** workflow keeps them in sync on
`main` and tags every pull request with the areas and Minecraft versions its
files touch. Issue forms (`.github/ISSUE_TEMPLATE/`) add `bug` /
`enhancement` automatically.

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
- A failed release run publishes nothing. Fix forward with the next number
  (as v1.0.33 → v1.0.34); the next run reuses jars only from releases that
  were actually published.
- The repo (or at least its releases) must stay public — launchers poll the
  release feed unauthenticated.

## The pieces

| File | Job |
|------|-----|
| `src/mods/live-modules.json` | THE list of shipped mod modules + the Minecraft versions each covers |
| `tools/release/plan.py` | which modules to build vs reuse (release) / build all (build-check) |
| `tools/release/notes.py` | release notes; `--backfill` rewrites past releases |
| `.github/workflows/build-mods.yml` | builds a list of modules in parallel (shared by both workflows) |
| `.github/actions/publish-launcher/` | bundle jars → `dotnet publish` → assert all jars present |
| `.github/workflows/build-check.yml` | every push: drift gate + all mods + launcher publish, ships nothing |
| `.github/workflows/launcher-release.yml` | every `launcher-v*` tag: the release (Run workflow = rehearsal) |
| `.github/workflows/relabel-releases.yml` | manual: rewrite every release's notes |
| `.github/workflows/labels.yml` + `tools/github/labels.py` | label definitions + PR auto-labels |
| `.github/workflows/boot-smoke.yml` | manual: headless boot test per version |
| `.github/workflows/deploy-website.yml` | `website/` → GitHub Pages on push to main |
