# Phase 0 - Cleanup and baseline

Covers ORIGIN_PROGRESS IDs P0-01 to P0-12, B-11, B-26, B-27. Runs first. Standing rules: `docs/overhaul/README.md` (S1-S12).
Phase branch: `claude/ovh-p0` from `origin/main`. Its first commit is a merge of `origin/claude/overhaul-docs`
(these docs), so the docs reach `main` at this phase's close.

## 1. Objectives
1. Merge and prove the two finished fixes: imported mods can't stop the game starting (`claude/launch-crash-fix2`)
   and the self-update zip is checked against its SHA-256 (`claude/shots-and-update-hash`).
2. Inventory every worktree (148 registered on 2026-10-25), branch (249 local, 102 remote) and stash; prove which hold
   no unique work; write the cleanup plan (`docs/overhaul/phase-0-inventory.md`, this is the "present the plan" step of 03);
   then remove only what is provably recoverable (class A, below). Unique work is archived or kept; deletes outside the project
   wait for Will (gate list).
3. Carry the three 2026-10-19 MEMORY.md entries that exist only as uncommitted text in the stale checkout onto main.
4. Refresh stale notes: CLAUDE.md "Current state", "Roadmap", "Brand"; MEMORY entries for v1.0.62-v1.0.65 and the
   10-21 Origin AI site work; the orchestrator's auto-memory files that the overhaul decisions made wrong.
5. Bring the main checkout (480 commits behind, dirty) onto current `main` without losing anything.
6. Record an accurate baseline (tests, CI, shots) that later phases compare against.

## 2. Original requests
- 03-work-order.md "PHASE 0 - CLEANUP" (all bullets) and the closing line "present the cleanup plan before performing
  any potentially destructive actions".
- 01 section 3 (audit, classify every previously requested fix; "Do not delete unfinished work without understanding its purpose").
- 00-decisions.md (backlog folded into the overhaul).

## 3. Inspect first
- `docs/overhaul/audit/ledger.md` sections 0 and 3-5 plus the appendix (worktree table): starting inventory, re-verify it.
- `docs/overhaul/audit/architecture.md` section 8 row P0 and section 11 row P0 (merge order, conflict files).
- `docs/overhaul/audit/launcher-systems.md` section 6 (self-update has no hash on main: `UpdateService.cs:128-150, 195-217`).
- Branch tips: `origin/claude/launch-crash-fix2` = 7fa5d69ca (2 ahead, `git merge-tree` clean against main on 10-25);
  `origin/claude/shots-and-update-hash` = b3e52020e (5 ahead, 359 behind; conflicts in `src/OriginLauncher.App/UI/ImportShots.cs`,
  `UI/Pages/SettingsPage.xaml`, `UI/RenderShots.cs`); `claude/launch-crash-fix` (97708fbc3) is a strict subset of fix2.
- Main checkout dirt (ledger section 5): group A website AI drafts (superseded by origin/main), group B `MEMORY.md` +60 lines
  (headings "2026-10-19 - Loading → main-menu hand-off v2 ...", "2026-10-19 - Loading bars never go backward ...",
  "2026-10-19 - Failed-reload guard + boot-smoke on real Java 17"), group C `.claude/settings.json` (22 read-only allow lines
  from the weekly permission scan) + untracked `.claude/.permissions-scan`, untracked `artifacts/` and `graphify-out/`.

## 4. Units
| Unit | Agent | Scope | Order |
|---|---|---|---|
| U0.1 Inventory | researcher (read-only) | every worktree, branch, stash, PR; writes `docs/overhaul/phase-0-inventory.md` on its own unit branch | parallel with U0.2 |
| U0.2 Crash-fix merge | desktop-app-dev | `src/OriginLauncher.App/Core/{ClientCoreMods.cs,Import/**,GameFolders/SharedMods.cs,Launch/CrashAnalyzer.cs}`, tests | parallel with U0.1 (own worktree) |
| U0.3 Update-hash merge | desktop-app-dev; **opus code-reviewer, security checklist** | `Core/Updates/**`, `MainWindow.xaml.cs`, `UI/{ImportShots,RenderShots}.cs`, `UI/Pages/{SettingsPage.xaml,.cs,HomePage.xaml.cs}`, `launcher-release.yml`, `docs/RELEASING.md`, `tools/release/check_consistency.py`, tests | after U0.2 is merged into `claude/ovh-p0` (both edit the tests csproj) |
| U0.4 Notes refresh | chore-runner | `CLAUDE.md` sections Current state / Roadmap / Brand; auto-memory files under `C:\Users\Will\.claude\projects\C--Users-Will-Documents-Minecraft-Mods-Origin-Client\memory\` | parallel (own worktree; no code) |
| U0.5 Permission allows | harness-engineer | `.claude/settings.json` in the phase branch | parallel |
| -- | orchestrator | present the cleanup plan to Will (section 9) and wait for his go | after U0.1 |
| U0.6 Main checkout catch-up | chore-runner | the main checkout only | after U0.1 (backup first) |
| U0.7 Deletions | chore-runner | class A items + in-repo class C after archiving; outside-project items held | after U0.6 |
| U0.8 Baseline | verifier | CI runs + numbers into ORIGIN_PROGRESS "Baseline" | after U0.2 and U0.3 are on `claude/ovh-p0` |
| Close | chore-runner | MEMORY.md, ORIGIN_PROGRESS.md, merge to main, push | last |

## 5. Changes, unit by unit
**U0.1 Inventory (no writes outside its own doc).** From the repo root run `git fetch origin --prune`, then:
- Worktrees: `git worktree list --porcelain`. For each path, `cd` into it and record: branch or detached SHA; last commit date;
  `git status --porcelain | wc -l`; real changes = `git status --porcelain | grep -v '^ D' | wc -l` (emptied sparse checkouts
  such as `%TEMP%\oit`, `orel`, `ocw`, `signin-wt`, `menu-redesign-wt` show thousands of ` D` lines and nothing else);
  `git rev-list --count origin/main..HEAD`; unique patches = `git cherry origin/main HEAD | grep -c '^+'`; location class
  (inside the repo `.claude/worktrees/` | `%TEMP%` | sibling folder under `Documents\Minecraft Mods\` | `Documents\Codex\`).
- Branches: `git branch --no-merged origin/main` and `git branch -r --no-merged origin/main`; for each, `git cherry origin/main <b>`
  ('+' lines = unique patches). Merged branches: count only.
- Stash: `git stash show --stat stash@{0}` (on legacy-1.8.9-rewrite).
- PR #34 (`integrate/sync-and-presence-2026-10-18`): `gh pr view 34`, `git cherry origin/main origin/integrate/sync-and-presence-2026-10-18`.
- Classify each item: **A safe** (no real changes, 0 unique patches), **B keep** (unique work; name what it holds),
  **C archive or hold** (unique but likely obsolete: tip preserved as `archive/<name>`, then removed; outside the project or in `Documents\Codex`: held for Will).
  Expected per the ledger: about 125 clean worktrees whose HEAD is in origin/main (A); `claude/import-flow` is patch-identical
  to main (A); `launch-crash-fix(2)` and `shots-and-update-hash` become A once U0.2/U0.3 merge; PR #34 set (`int-wt`, `iw`,
  `sync-wt`, `wt`), `claude/launcher-poster-pages`, `backup/local-wip-2026-10-18`, `icons-wt` (+427 dirty lines, superseded),
  `Origin Store Preview` (+2), `claude/bold-faraday-024c25` (adds artifacts/ and graphify-out/ to .gitignore) are B or C.
- Never classify as removable: the main checkout, `claude/dev-shots` (CI shot store), `claude/overhaul-docs` until Phase 0 closes,
  any `claude/ovh-*` branch, release tags, the scratch worktrees `audit-wt` and `wt-docs` until Phase 0 closes.
- Output: the inventory table plus a one-screen summary (counts per class, the B and C lists with one line each).

**U0.2 Crash-fix merge.** `git worktree add -b claude/ovh-p0-crashfix <path> origin/claude/ovh-p0`, then
`git merge --no-ff origin/claude/launch-crash-fix2` (expected clean). Do not rewrite the fix. Run
`dotnet test src/OriginLauncher.Tests`: the new tests (ClientCoreImportTests, ImportSharedModsTests, SharedModsTests additions,
CrashAnalyzerTests with the real 2026-10-20 Feather fixture in CrashFixtures.cs) must pass and the total must be >= 1434 plus the
added tests. Push; build-check must go green. What it does (for the verifier): `ImportedModFit.cs` keeps imported mods that
can't load from blocking boot; `SharedMods.cs` stops a read-only empty account mods folder from blocking the merge;
`ClientCoreMods.cs` never imports Feather/Lunar/Badlion/LabyMod core jars; `CrashAnalyzer.cs` blames them when a crash is inside them.

**U0.3 Update-hash merge.** After U0.2 is in `claude/ovh-p0`: `git worktree add -b claude/ovh-p0-updatehash <path> origin/claude/shots-and-update-hash`,
then `git merge origin/claude/ovh-p0`. Resolve exactly three conflicts, keeping main's current structure and re-adding only the branch's intent:
- `UI/Pages/SettingsPage.xaml`: main's ScrollViewer is already named `Scroller` (used by the rail scroll-spy, SettingsPage.xaml.cs:432-461).
  Do not rename it to the branch's `PageScroll`; point the branch's `TourScrollSettingsToSync` / `TourScrollSettingsToTop`
  helpers (MainWindow.xaml.cs, SettingsPage.xaml.cs) at `Scroller` and at wherever the Settings Sync row now lives.
- `UI/RenderShots.cs`: keep main's tour; add the branch's shots `14b-settings-sync` and `24b-min-settings-sync` (scroll to the Sync row,
  snap, scroll back to top so shot 24 still shows the top).
- `UI/ImportShots.cs`: keep main's import tour (it moved on: grid page, manifest, ledger); add `Demo.Scanning` and shot
  `38b-import-drawer-scanning` only if the scan pane still exists on main; otherwise drop that shot and say so.
Run all launcher tests (UpdateVerifierTests, UpdateGateTests, UpdateCleanupTests must pass; locked-file cleanup test is Windows-only by design).
Then dispatch `launcher-release.yml` with workflow_dispatch on `claude/ovh-p0-updatehash` (builds everything, skips publishing,
docs/RELEASING.md line ~59) and confirm the artifact holds `OriginLauncher-win-x64.zip.sha256` whose hash equals the zip's.
Security checklist for the opus reviewer: the hash is checked before `ExtractToDirectory`; the sidecar comes over HTTPS from the
same release; what happens when the sidecar is missing or malformed (must not install an unverified zip; must not block play for
good); zip-slip on extract; the robocopy script quotes every path; stale-zip cleanup deletes only Origin's own update files under
`OriginPaths`; a late quiet retry cannot replace a newer offer; no token or user path is logged.

**U0.4 Notes refresh.** In `CLAUDE.md` replace only these sections:
- "Current state": latest release `launcher-v1.0.65` (d2282be60, 2026-10-20; read `git tag --sort=-creatordate` to confirm);
  main also carries the 10-21 website "Origin AI helper" (removed in Phase 1); the two Phase 0 fixes are on main but not in a
  release until the overhaul's single release (after Phase 7); the overhaul is running, see `ORIGIN_PROGRESS.md`. Keep the Auth 403 / OfflineTestMode line.
- "Roadmap (open items)": one line pointing to `ORIGIN_PROGRESS.md`, plus the items outside the overhaul: 1.21.9 (pulled),
  1.18.1 (no stable Sodium/Iris), light theme, after-shaders world-overlay pass on modules other than 1.21.1. Drop the merged
  cosmetics-outer-layer, screen-open-lag and Button Style lines (merged; their in-game checks live in ORIGIN_PROGRESS).
- "Brand": "Logo: the official Origin logo asset only, drawn through the central logo reference (Will, 2026-10-25: `textures/ui/origin_logo.png`,
  same art as `Assets/origin.ico`; wordmark `Assets/Brand/wordmark.png`); never redrawn or approximated. Identity: Workbench (Will, 2026-10-25): moss-black neutral base, parchment text,
  emerald signal for Play, toggles, progress and selected states only; soft-hue accents in small tiles, dots, badges, progress and
  selected rings, never big colour fills and never in glows; the big Home version numeral stays neutral. In-game main menu and
  other non-Mod-Menu surfaces get the launcher's rounded, softer surfaces; the in-game Mod Menu keeps its approved look. Spec:
  docs/ORIGIN_UI_SYSTEM.md (rewritten in Phase 2)."
- Under "Token discipline", nothing changes.
Auto-memory (orchestrator's index `MEMORY.md` there and its files): mark `no-windows-on-wills-pc.md` superseded for the overhaul
(04: real-install testing allowed, README S1); mark `design-identity-is-locked.md` and `ui-redesign-decisions.md`
superseded by the 2026-10-25 decisions (rounded main menu, Workbench); mark `paused-playtest-backlog-2026-10-20.md` folded into
`ORIGIN_PROGRESS.md`; add one entry "Origin overhaul: docs/overhaul + ORIGIN_PROGRESS.md are the plan of record". Edit text, delete nothing.

**U0.5 Permission allows.** From the backup copy of the stale checkout's `.claude/settings.json` (U0.6 makes it; until then read
`git diff -- .claude/settings.json` in the main checkout, read-only), add to the phase branch's `.claude/settings.json` only the
allow lines that are read-only and not already present. Do not add `.claude/.permissions-scan` (it is a local stamp).

**U0.6 Main checkout catch-up** (in `C:\Users\Will\Documents\Minecraft Mods\Origin Client`, after U0.1; authorized by 04, nothing is lost: backup first):
1. `git status --porcelain` must list exactly the ledger section 5 items (6 modified tracked files, the 3 untracked
   `website/assistant.html`, `website/css/assistant.css`, `website/js/assistant.js`, `.claude/.permissions-scan`, `artifacts/`,
   `graphify-out/`). Anything else: stop and report.
2. Backup: append `.claude/backups/` to `.git/info/exclude` (local ignore; `.claude/worktrees/` is already listed there),
   create `.claude/backups/main-checkout-2026-10-25/` and copy every modified and untracked file listed above into it, keeping
   relative paths (skip `artifacts/` and `graphify-out/`; they stay where they are).
3. `git stash push -m "pre-overhaul main checkout 2026-10-25" -- MEMORY.md .claude/settings.json website/index.html website/store.html website/terms.html website/css/site.css`.
4. Move the three untracked `website/*assistant*` files into the backup folder (origin/main tracks those paths, so they would block the update).
5. `git fetch origin`, then `git merge --ff-only origin/main` (local main is 0 ahead; if the fast-forward is refused, stop).
6. Leave `artifacts/`, `graphify-out/` and `.claude/.permissions-scan` untouched. Never drop any stash (kept by default; dropping it is irreversible).
7. Report `git log -1 --oneline` and `git status --porcelain | wc -l`.

**U0.7 Deletions** (04 authorizes autonomous work but no irreversible operation, so only recoverable removals run; one command
per item; stop on the first unexpected error):
- Inside the repo, class A worktrees: `git worktree remove <path>` (no `--force`). Emptied sparse checkouts need `--force`; only
  those the inventory shows with zero tracked or untracked files. A locked or in-use folder: skip and list it. Then `git worktree prune`.
- Outside the repo (`%TEMP%\oit|orel|ocw|iw|recipe-1214|origin-settle-gate`, the `%TEMP%\claude\...` agent worktrees, sibling folders
  `origin-tooltips-wt`, `Origin shader test`, `Origin Store Preview`): gate "deletes outside the project": listed under Needs Will, never deleted by an agent.
- `Documents\Codex\...\origin-ai-site-publish`: agents never work in `Documents\Codex`; ask Will to delete that folder himself, then `git worktree prune`.
- Branches: merged local branches `git branch -d` (refuses unmerged by design). Unmerged class C branches (unique but obsolete per the inventory): first preserve the tip
  as `git push origin <sha>:refs/heads/archive/<name>`, then `git branch -D`. Remote: `git push origin --delete <name>` only for merged
  names or names already preserved under `archive/`. Never `git clean`, never `reset --hard` (denied anyway).
- Afterwards: `git worktree list | wc -l`, `git branch | wc -l`, `git branch -r | wc -l` into the report.

**U0.8 Baseline** (on `claude/ovh-p0` after U0.2 and U0.3 merged): record in ORIGIN_PROGRESS "Baseline": the build-check run id and
its three test-project counts; one `launcher-shots` run (label `p0-baseline`); one `dev-shots` run with `modules=all set=design
label=p0-baseline` (these `menu_*` shots are the Mod Menu reference for S4); one `boot-smoke` run `modules=all` (which modules boot,
which fail; failures become ORIGIN_PROGRESS items, not Phase 0 work); one `launch-time` run via commit message
`[launch-time base=origin/main versions=1.8.9,1.16.5,26.3 label=p0-baseline]` (also proves the U0.2 fix did not break provisioning).
Each workflow once (S3).

**Close.** One chore-runner: (a) append to `MEMORY.md` the three 2026-10-19 entries verbatim from the U0.6 backup (headings unchanged,
add "(carried over 2026-10-25 from the stale checkout)" under each heading), then one entry per release v1.0.62-v1.0.65 from
`git log --oneline launcher-v1.0.61..launcher-v1.0.65` titles, one for the 10-21 Origin AI site work (311f4fc72..de6b3eb6a), and the
Phase 0 units' MEMORY ENTRY lines; (b) update ORIGIN_PROGRESS.md statuses and evidence; (c) merge `claude/ovh-p0` into `main` and push
`main` (README "How a phase runs" step 5; this push also carries the docs); (d) in the main checkout `git pull --ff-only` if clean.

## 6. Must NOT change
- The two fixes' behaviour (merge them, don't rework them); no launcher UI change beyond the three conflict resolutions.
- Nothing is deleted before the U0.1 inventory is written and reviewed; nothing unique is deleted without an `archive/<name>` branch first;
  nothing outside the project is deleted by an agent.
- `MEMORY.md` history: append only. CLAUDE.md: only the three named sections.
- `claude/dev-shots`, release tags, every stash, PR #34 (held for Phase 3), Will's install (no testing needed here), anything in `Documents\Codex`.

## 7. Dependencies
None upstream. Every later phase depends on Phase 0 (Phase 1 edits SettingsPage after U0.3; Phase 5 builds Core/Compat on
U0.2's `ImportedModFit`; S4 needs U0.8's Mod Menu reference shots).

## 8. Verification (the verifier names each result)
- U0.2: `dotnet test` count and 0 failures; build-check green on the unit branch; `git diff origin/claude/launch-crash-fix2 HEAD -- src/` shows no
  edits to the fix beyond merge context.
- U0.3: UpdateVerifier/Gate/Cleanup tests pass; launcher-shots run shows `14b-settings-sync` and `24b-min-settings-sync` with the Sync row
  visible (and `38b` if kept); the `launcher-release.yml` dispatch artifact contains a `.sha256` that matches the zip (verifier recomputes it);
  opus security review passes the section 5 checklist.
- U0.4: `git diff` touches only the three CLAUDE.md sections; Brand line text matches section 5.
- U0.6: main checkout `git log -1` equals `origin/main`; backup folder lists every file from step 1; `git stash list` shows the new stash.
- U0.7: post-deletion counts; every class B item still exists (or as `archive/*`).
- U0.8: run ids and numbers recorded; nothing re-run.
- Close: `git log origin/main -1` contains the Phase 0 merge; `grep -c '^## 2026-10-19 - Loading' MEMORY.md` on main >= 1.

## 9. Needs Will (held; the rest of Phase 0 and the next phases continue)
1. **Deletes outside the project** (gate list): the `%TEMP%` worktree folders, the sibling folders and the Codex folder named in U0.7.
   The report lists them with sizes and "safe: no unique work" or "holds: <what>"; Will deletes them or says go naming them.
Decided by the orchestrator under 04 (recorded in ORIGIN_PROGRESS Decisions, Will can override):
- **PR #34** (Settings Sync section + installer Customize option + Discord always-on): held and folded into Phase 3 Settings work
  (U3.7b), because the Settings page is redesigned there anyway.
- **Old stash** `stash@{0}` (legacy-1.8.9-rewrite, July): kept.
- **Main checkout catch-up** (U0.6): run, with the backup first.

## 10. Parallel and agents
Phase 0 runs alone (every track starts after it). Inside it: U0.1 (researcher) and U0.2 (desktop-app-dev) in parallel, separate
worktrees; U0.3 after U0.2; U0.4 (chore-runner) and U0.5 (harness-engineer) in parallel with U0.3; U0.6 then U0.7 (chore-runner,
main checkout, nothing else writing there); U0.8 (verifier dispatches the CI runs) last. Judge: `architect` (back-end weights).

## 11. Completion criteria
Both fixes on `main` with green CI and both reviews passed; cleanup done exactly as section 5 allows, with counts reported; main checkout on current
`main`; the three MEMORY entries and the release entries on `main`; CLAUDE.md sections refreshed; baseline recorded in ORIGIN_PROGRESS;
**the judge passes Phase 0 (`docs/overhaul/JUDGE.md`: 95/100, no auto-reject)**; phase branch merged to `main` and pushed; report
sent. No release here: the one release (`launcher-v1.0.66`+) is tagged after Phase 7 (README S8).
