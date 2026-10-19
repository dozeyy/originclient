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
3. **Build launcher + publish release** (Windows, ~4 min) — bundles all 16
   jars into the launcher (`.github/actions/publish-launcher`), **asserts
   every live jar is really in it**, zips it (`OriginLauncher-win-x64.zip`),
   builds the small Origin installer `OriginLauncher-Setup.exe`
   (`.github/actions/build-installer`) and **smoke-tests them**: a silent
   install that downloads the newest published release for real, then a
   silent install of this build's zip over it (every file checked
   byte-for-byte plus shortcuts, Add/Remove Programs entry and
   Start-with-Windows), then a silent uninstall checked to remove all of it.
   Only then does it hash the zip and publish the GitHub release with the
   setup.exe, `OriginLauncher-win-x64.zip` and
   `OriginLauncher-win-x64.zip.sha256`, titled "Origin Launcher 1.0.N".

Before this pipeline (≤ v1.0.39) the same work ran one version after
another on a single Windows machine and took 12-40 minutes.

Within ~2 minutes (or instantly on window focus), every running launcher
sees the new release and lights the download badge. Updates are mandatory:
Play refuses until the player clicks the badge, which shows the download as
a full-screen progress bar and restarts into the new build.

## Self-update integrity (the .sha256 asset)

Every release carries `OriginLauncher-win-x64.zip.sha256` (sha256sum format,
`<64 hex>  OriginLauncher-win-x64.zip`), written by the "Hash the self-update
zip" step after the zip is final. The launcher (`Core/Updates/UpdateService.cs`
+ `UpdateVerifier.cs`) fetches it first, hashes the zip while it downloads and
checks it **before anything is unpacked**:

- **Mismatch, or fewer bytes than Content-Length** -> the download is deleted,
  nothing is extracted or applied, and the reason goes to
  `%LocalAppData%\OriginLauncher\logs\update.log`. **A failed check never
  blocks play** (mandate 4): the update leaves the mandatory-update gate at
  once, the badge hides, and the player launches the version they have
  (`Core/Updates/UpdateGate.cs`). It is retried quietly: ignored for 30 min,
  then fetched and verified in the background with no UI; if that fails again
  the wait doubles (1 h, 2 h, 4 h, capped at 6 h); a new launcher start retries
  at once. Only a download that has verified is published to the gate (badge
  lights, Play gated), so a broken release can never block play.
- **No hash published** (a release cut before this step existed, or the asset
  missing) -> accepted unverified and logged, so old releases still update.
  With no sidecar the launcher also uses GitHub's own `digest` field for the
  zip (the installer already relies on it), but only a clean `sha256:<64 hex>`:
  any other digest (another algorithm, empty, missing, a future format) counts
  as "no hash", never a mismatch, so a GitHub API change cannot brick updates.
- **A sidecar that is listed but cannot be parsed** is a verification failure
  (same quiet back-off as above), never treated as "no hash". A plain network
  failure (offline, stalled connection, HTTP error) is not a verification
  failure and behaves as before: the badge stays and offers "click to retry".
- A connection with no bytes for 60 s aborts the download; the half-written
  `.part` file is always deleted, and the zip only appears under its real name
  once verified.

The hash proves the bytes are the ones the pipeline published (corruption, a
truncated or swapped CDN copy). It does not defend against someone who can
replace release assets, since the hash sits beside the zip: signing would be a
separate step. `check_consistency.py` fails CI if the workflow stops
publishing the asset the launcher looks for. Tests:
`src/OriginLauncher.Tests/UpdateVerifierTests.cs`. Never rename the `.sha256`
asset (installed launchers key on it; an absent one is accepted, see above).

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

## The installer (OriginLauncher-Setup.exe)

Origin's own app (`src/OriginSetup`), not a generic wizard: the launcher's
look (it links the launcher's theme files), Welcome → optional Customize
(install location, memory for Minecraft, default version, Start with
Windows) → Installing → Done, and a matching uninstaller behind
Add/Remove Programs.

- **Small, needs internet**: .NET Framework 4.7.2 (built into Windows
  10/11), so the setup is ~1 MB and needs no runtime. It **downloads the
  newest released launcher** — the latest release's
  `OriginLauncher-win-x64.zip`, the same zip installed launchers self-update
  from — on the Installing screen, with a real progress bar (MB and %).
  So an old setup.exe still installs the newest Origin, and a release needs
  no new setup.exe to reach new players.
- **Finding the newest release** (`Engine/ReleaseFeed.cs`): the GitHub
  releases API (gives size + SHA-256), falling back to the
  `github.com/…/releases/latest` redirect when the API is rate-limited — no
  API call, and still pinned to one version. Owner/repo/tag prefix/zip name
  must match the launcher's `UpdateService` (`check_consistency.py` checks).
- **Network trouble** (`Engine/Download.cs`): TLS 1.2 forced on; a dropped
  or stalled (30 s) connection resumes where it stopped, up to 4 tries; then
  a plain-words problem screen (no internet / connection dropped / server
  trouble / damaged download) with **Try again**.
- **Safe by construction**: downloads and unpacks into a staging folder
  inside the install folder, verifying the download's size and SHA-256 and
  every file's size and CRC-32, before touching the real install — so
  cancelling, a dropped connection or a damaged download never leaves a
  broken Origin. Takes over installs made by the old Inno Setup installer in
  place (same Add/Remove Programs entry). The installed `uninstall.exe` is a
  copy of the setup app.
- `--source <zip>` installs from a local zip instead of downloading — CI's
  smoke test uses it for the build it just made (not published yet).
- Customize choices reach the launcher through a small hand-off file it
  applies on its next start (`Core/Install/SetupChoices.cs`).
- **Every build-check renders every installer screen to pictures** (the
  "installer-screens" artifact on the run) — review UI changes there.
- Engine tests: `dotnet test src/OriginSetup.Tests -f net8.0` (no network:
  downloads run against an in-memory fake server).

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
| `.github/actions/build-installer/` | zip the launcher, build OriginSetup, smoke-test download install + install of this build + uninstall (+ screen renders) |
| `src/OriginSetup/` | the installer app (downloads the launcher zip from the newest release) |
| `tools/release/check_consistency.py` | fails CI when launcher/installer copies of a fact drift apart |
| `.github/workflows/build-check.yml` | every push: drift gate + all mods + launcher publish, ships nothing |
| `.github/workflows/launcher-release.yml` | every `launcher-v*` tag: the release (Run workflow = rehearsal) |
| `.github/workflows/relabel-releases.yml` | manual: rewrite every release's notes |
| `.github/workflows/labels.yml` + `tools/github/labels.py` | label definitions + PR auto-labels |
| `.github/workflows/boot-smoke.yml` | manual: headless boot test per version |
| `.github/workflows/deploy-website.yml` | `website/` → GitHub Pages on push to main |
