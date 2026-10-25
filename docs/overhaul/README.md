# Origin overhaul: how to run it

Written 2026-10-25 against origin/main `de6b3eb6a` (latest tag `launcher-v1.0.65`). The master
checklist is `ORIGIN_PROGRESS.md` at the repo root; it says which phase is current and what is next.

## Files
| Path | What |
|---|---|
| `ORIGIN_PROGRESS.md` | The one master checklist: every requirement, phase, status, evidence. Updated at every phase close. |
| `docs/overhaul/phase-0-cleanup.md` ... `phase-7-mod-updates.md` | One execution prompt per phase. The orchestrator turns each unit row into an agent brief. |
| `docs/overhaul/requests/00-04` | Will's words, unchanged. On conflict: 04 (overnight authorization, newest) wins on autonomy, testing, release and the four answers; 03 wins on order and process; 02 wins over 01 on design; 00 records his first answers. |
| `docs/overhaul/JUDGE.md` | The phase judge: Will's rubric word for word (95/100 pass, auto-rejects, loop). Every phase's completion criteria end with it. |
| `docs/overhaul/audit/*.md` | Read-only audits of origin/main `de6b3eb6a` (2026-10-25). Their path:line references point at that commit; re-check a line before editing it. Paths in them that start with `scratchpad\` or `audit-wt` are gone; the repo path is the part after the worktree root. |
| `docs/overhaul/design/concepts.md`, `A-Workbench-*.jpg`, `concepts-mock.html` | The Workbench concept Will chose (direction A). **The ring logo in the mock is a recreation and is forbidden** (02 section 1); it is never a reference for the logo. |

## How a phase runs
1. Orchestrator reads `ORIGIN_PROGRESS.md` (How to resume), then the current phase prompt.
2. Will authorized autonomous completion (04): decide small and within-design questions alone and record each in
   `ORIGIN_PROGRESS.md` Decisions as "decided by the orchestrator, Will can override". Only the "Needs Will" items
   (gate list, money, credentials, production infrastructure, design-direction changes) wait; other work continues.
3. Dispatches the unit rows. Each unit works in its own worktree off the phase branch
   `claude/ovh-p<N>` (cut from `origin/main`), on a unit branch `claude/ovh-p<N>-<unit>`.
   Parallel writers always get separate worktrees (`isolation: "worktree"`, or
   `git worktree add -b <branch> <path> claude/ovh-p<N>`). Run plain `git` from inside the
   worktree; `git -C` is denied.
4. Each unit: code-reviewer and verifier in parallel, separate contexts, both pass with evidence
   (S2); then the unit branch merges into `claude/ovh-p<N>`.
5. Phase close (every phase): `ORIGIN_PROGRESS.md` updated, MEMORY entries appended by one
   chore-runner, **`claude/ovh-p<N>` merged into `main` and `main` pushed** after review and verify
   pass, short report to Will. Merge from a scratch worktree:
   `git fetch origin` / `git worktree add <path> origin/main` / `git merge --no-ff origin/claude/ovh-p<N>` /
   `git push origin HEAD:main`. Then, in the main checkout only if `git status --porcelain` is empty:
   `git pull --ff-only`. The judge (`JUDGE.md`) must pass the phase before this merge.
6. Parallel tracks: see "Which phases run in parallel" below; each track has its own phase branch and worktrees.

## Standing rules (every phase; each prompt repeats the ones that bite there)
- **S1 Testing.** CI is the default visual proof: `launcher-shots.yml` (windows-latest; on push to `claude/**`
  touching `src/OriginLauncher.App/**`, or dispatch with `label`), the `installer-screens` artifact of `build-check.yml`
  (`src/OriginSetup/UI/RenderTour.cs`), `dev-shots.yml` (Xvfb; dispatch inputs `modules`, `set` = design / world /
  handoff / style, `style`, `label`, `video`, `framelog`; also on push to `claude/**` touching `src/mods/versions/**`),
  `boot-smoke.yml` (dispatch `modules`), `boot-time.yml`, `launch-time.yml` (Windows, real provisioning; trigger = head
  commit message `[launch-time base=<ref> versions=<list> ...]`). Look at a shot folder's `_sheet.jpg` before any single
  shot. **Will's real install is allowed too (04, 2026-10-25; supersedes the "no windows on Will's PC" memory for this
  overhaul):** agents may run his installed Origin launcher with his accounts and launch Minecraft on his PC. They never
  delete worlds, saves, profiles or accounts, never change credentials, and copy anything to
  `%LocalAppData%\OriginLauncher\backups\overhaul-<date>\` before changing it. Never overwrite a jar a running game
  holds. Never copy a dev build into his install; it updates only through a real release.
- **S2 Acceptance.** A unit is accepted only when a code-reviewer and a verifier, run in parallel in
  separate contexts, both pass it with evidence. Self-update, downloads, hashes, tokens, dependency
  pins and anything that publishes (a website push to main) get an **opus** code-reviewer with the
  security checklist (risk floor). Reviewers are never junior to the author. A phase is complete only when the
  judge passes it (`JUDGE.md`: 95/100, every critical requirement passing, no auto-reject).
- **S3 One pass.** One review-and-verify pass per change; a fix plus green CI is accepted, no
  second round. Never re-run CI on a commit that already passed. Scope CI to touched modules.
- **S4 Mod Menu frozen.** `OriginModMenuScreen` (and its `OriginDeck` styling) is never visually
  changed. Bug, performance and internal fixes are allowed; prove "no visual change" by comparing the
  `menu_*` dev-shots of the change against the Phase 0 baseline shots of the same module.
- **S5 Real logo only.** Always the official Origin logo asset, never a recreation (CSS, XAML shapes,
  text, generated art). After Phase 2 every surface draws it through the central logo reference;
  before Phase 2, do not touch logo rendering.
- **S6 Mod code layout.** Shared mod code lives in `src/mods/shared`, propagated with
  `python tools/shared-sync/sync.py` (CI runs `--check`). A per-version fork of a shared file is
  listed in that module's `overrides.txt`. Pre-1.20 modules and 1.8.9 are hand-ported
  (`.no-shared-sync`); use the `origin-port` skill (`.claude/skills/origin-port/`) for every port.
- **S7 Website.** Website changes on `main` auto-deploy (`deploy-website.yml`; Vercel serves
  originclient.org). A website commit reaches `main` only after an independent judge agent scores it
  9/10 or higher (fun, polish, mobile, accessibility, honest claims). Never claim "every version".
- **S8 Branches and releases.** Pushing `claude/*` branches is allowed (CI never deploys from them).
  Merging the phase branch into `main` and pushing `main` is part of every phase close. **One** launcher release
  (`launcher-v1.0.66` or later) is tagged by the orchestrator once every phase's tests, CI boots and judge have passed
  (04: "Yes, if it all passes"); no per-phase releases. From a worktree:
  ```
  git fetch origin --tags
  git tag launcher-v1.0.<N> origin/main
  git push origin launcher-v1.0.<N>
  ```
  `<N>` = the newest tag (`git tag --sort=-creatordate`) plus one. If the tag push fails (cloud sessions drop tag
  pushes), the report gives Will the same lines led by `cd "C:\Users\Will\Documents\Minecraft Mods\Origin Client"`.
  Release notes come from commit titles: write them for players.
- **S9 Data safety.** Settings writes only through `SettingsStore.Update`; user data only under
  `OriginPaths`; the shared `instances/<v>/mods/` folder is add / delete / rename-swap only. Never
  delete user profiles, saves or worlds. Real-install testing follows S1. Only the AI assistant Worker
  (`origin-assistant-api`) is retired; the cosmetics service (`services/cosmetics`: store, owned capes,
  friends/social) stays exactly as it is and its production deployment is never touched.
- **S10 Memory.** `MEMORY.md` is grep-only and has one writer: the closing chore-runner, given
  every unit's MEMORY ENTRY.
- **S11 Held for Will (other work continues).** Deletes outside the project, anything that costs money, credential
  changes, production infrastructure (Cloudflare, Anthropic, Stripe dashboards and deploys), replacing the design
  direction, irreversible operations. Dependency updates and pin changes are authorized inside Phase 7's test gate (04);
  a new dependency elsewhere is decided by the orchestrator and recorded in Decisions.
- **S12 Honesty.** Mark something working only after it was verified; otherwise "built, not tested
  yet". Never invent numbers, especially FPS. CI FPS is never a player number.

## Report to Will after every phase (format: 03, with 04's headings)
```
Phase N/7 - <name>
Done
* <plain words, one line each>
Original requests
* <request>: Working / Built, not tested yet / Not working
Judge score
* <score>/100 - pass / not yet (main problems)
Needs testing
* <what only Will can check, e.g. feel or his real GPU>
You need to do
* <exact steps, or "Nothing">
Next
* Phase N+1: <name>
```
Only verified items say "Working". Short, no jargon. Full technical detail goes in `ORIGIN_PROGRESS.md`.

## Which phases run in parallel (04: "parallel where independent, no conflicting modifications")
Phase reports still go out in phase order; tracks only overlap the work.
| Track | Phases | Starts after | Shares files with | Rule |
|---|---|---|---|---|
| A (UI) | 1 -> 2 -> 3 -> 4 | Phase 0 close | B (launcher XAML), C (1.8.9 title/loading) | Sequential inside the track. |
| B (back end) | 5.1, 5.2 | Phase 0 close | A only in `Core/` call sites | Own branch `claude/ovh-p5`; UI units 5.3-5.5 start after Phase 3 U3.0 (tokens + base styles) merges. |
| C (1.8.9 perf) | 6.1 harness, E3, 6.3 investigation | Phase 0 close | A's Phase 4 1.8.9 wave | Own branch `claude/ovh-p6`; Phase 6 code in `versions/1.8.9` merges before Phase 4 starts its 1.8.9 wave, which then rebases. Presets UI (6.2) after Phase 3 U3.0. |
| D (deps) | 7 audit (read-only) | Phase 0 close | every module's build files | Updates themselves run per module after Phase 4 has merged that module's port, so boot-smoke baselines are clean. |
Every parallel writer: its own worktree and unit branch (`isolation: "worktree"`). Two tracks never edit the same file in the
same window; if they must, the later one rebases and re-runs its checks.

## Agents (Origin's `.claude/agents/` first, then `~/.claude/agents/`)
desktop-app-dev (launcher C# logic), ui-builder (WPF/XAML UI), fabric-mod-dev (mod code, Fabric
modules and the 1.8.9 Forge module), mc-port-parity (cross-version parity), website-dev,
design-director (opus; directions and previews), design-reviewer (opus; judges renders; phase judge for UI phases),
perf-profiler, crash-investigator, render-debugger, bug-hunter, researcher, architect (phase judge for back-end phases),
verify-auditor (proves a check can fail), harness-engineer (Claude config), code-reviewer, verifier,
release-packager, chore-runner (haiku; mechanical edits, MEMORY, merges).

Will's agent list (04) mapped to these: Launcher UI / Workbench = `ui-builder` + `desktop-app-dev` (Phases 1-3) ·
main menus + loading screens = `fabric-mod-dev` + `mc-port-parity` (Phase 4) · compatibility architecture = `architect`
then `desktop-app-dev` (5.1-5.3) · importing + profiles = `desktop-app-dev` + `ui-builder` (5.2, 5.5) · crash diagnosis =
`crash-investigator` then `desktop-app-dev` (5.4) · 1.8.9 performance = `perf-profiler` + `fabric-mod-dev` (Phase 6) ·
mod dependency updates = `researcher` then `fabric-mod-dev` (Phase 7) · testing / regression = `verifier` (+
`verify-auditor` for new checks), every phase.

## Verification toolbox
- Launcher: `dotnet test src/OriginLauncher.Tests` (baseline 1434 passed, 0 failed on `de6b3eb6a`;
  the count must never drop), `src/OriginLauncher.UiTests`, `src/OriginSetup.Tests` (CI runs all
  three in `build-check.yml`).
- Repo gates: `python tools/shared-sync/sync.py --check`, `python tools/release/check_consistency.py`.
- Mods: `.\gradlew.bat build` inside `src/mods/versions/<mc>`; boot proof = `boot-smoke.yml` with
  zero `Mixin apply .* failed` lines and Origin's self-check lines in the run log.
- Website: no build step; link resolver over `website/` href/src/url(), console check, renders at
  375 and 1280 px wide.
