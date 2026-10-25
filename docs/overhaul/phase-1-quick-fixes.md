# Phase 1 - Quick fixes

Covers ORIGIN_PROGRESS P1-01 to P1-10, B-23, B-32. Standing rules: `docs/overhaul/README.md`. Phase branch `claude/ovh-p1` from `origin/main`
(after Phase 0 closed). Website units push to `main` on their own once judged 9/10 (S7); launcher units reach `main` at phase close.

## 1. Objectives
1. Settings rail: the first click on "Origin Client" opens Origin Client (root-cause fix, not a workaround).
2. Developer mode gone from the public launcher; dev access kept for dev builds and CI harnesses, without hidden-button "security".
3. Website: every public AI feature removed; the GitHub card restored; phone navigation fixed. Every AI-assistant reference goes,
   repo-wide (website, launcher, docs a player can see, CI). Only the AI assistant Worker (`origin-assistant-api`) is retired (04):
   the cosmetics service (`services/cosmetics`: store, owned capes, friends/social) and its deployment stay exactly as they are.
4. Launcher: "Website" button becomes a "Store" card that opens the configured Origin Store URL, proven by a check, not by eye.
5. Nothing else changes (regression-checked).

## 2. Original requests
03 "PHASE 1 - QUICK FIXES" (all seven bullets). 01 section 9 (rail bug, remove public developer mode), section 10 (Store card),
section 17 (remove public AI), section 19 Website QA. 02 section 6 (Store: "use the actual Origin Store destination configured in the project").

## 3. Inspect first
- Rail bug, static trace in `audit/launcher-ui.md` section 6: `UI/Pages/SettingsPage.xaml.cs:432-461` (`Rail_Checked`, `Scroller_ScrollChanged`,
  the `atBottom ||` clause makes every item current, the loop ends on Developer); rail RadioButtons `SettingsPage.xaml:83-106`.
- Dev mode, `audit/launcher-ui.md` section 7 and `audit/architecture.md` section 7: `SettingsPage.xaml:315-331` (SecDeveloper,
  OfflineTestToggle), handlers `SettingsPage.xaml.cs:249-260`, load `:57`; `LauncherSettings.OfflineTestMode` (`Core/Models/LauncherSettings.cs:38`);
  `UpdateService.IsDevBuild` (`Core/Updates/UpdateService.cs:56`); harness sets it in code (`LaunchTimingHarness.cs:52`). CLI flags
  `--render-shots --import-plan --launch-timing --startup-trace --import` are not UI and stay.
- Website, `audit/website.md` section 1: `website/assistant.html`, `css/assistant.css`, `js/assistant.js` (endpoints
  `origin-assistant-api.will-f11.workers.dev`), `index.html:48` nav link, `index.html:217-218` "Minecraft helper" card (replaced the
  "Public code / Read every line." card: restore from `git show 311f4fc72^:website/index.html`), `store.html:43`, `css/site.css:306,309`
  (`:not(.nav-assistant)` replaced `:not(.nav-home)`, which hides Home on phones), `terms.html:33` date and section 10 at `:123`.
- Store, `audit/launcher-ui.md` section 8 + `audit/website.md` section 3: Website button `MainWindow.xaml:115-126`, handler
  `MainWindow.xaml.cs:601-602` (hardcoded `https://originclient.org/`, via `Core.Promos.ExternalLink.Open`); configured store page
  `services/cosmetics/src/stripe.js:31` STORE_PAGE and `docs/STORE.md:1` = `https://originclient.org/store`.

## 4. Units
| Unit | Agent | Scope | Order |
|---|---|---|---|
| U1.1 Rail + dev gate | desktop-app-dev | `UI/Pages/SettingsPage.xaml(.cs)`, `App.xaml.cs` (startup reset), `src/OriginLauncher.UiTests`, `UI/RenderShots.cs` | parallel (own worktree) |
| U1.2 Store card | ui-builder | `MainWindow.xaml(.cs)` BarActions, new `Core/Promos/OriginLinks.cs`, `tools/release/check_consistency.py`, `UI/RenderShots.cs` | parallel; merges after U1.1 (both add shots to RenderShots.cs) |
| U1.3 Website | website-dev; judge = design-reviewer; **opus code-reviewer (publishes)** | `website/**` only | parallel; pushes website-only commits to `main` after 9/10 |
| U1.4 Regression | verifier | whole phase branch | after U1.1-U1.3 merged |

## 5. Changes
**U1.1** First reproduce in a UiTest (headless WPF): load SettingsPage at the default 1180x740 size, click the Client rail item once,
assert Client is checked and the scroller sits at Client's section (fails today). Then fix the cause: an explicit rail click wins over
the scroll-spy until the requested scroll settles (record the clicked item; `Scroller_ScrollChanged` keeps it while the offset is moving
toward its target or is clamped at the bottom; the spy resumes on the next user scroll). `atBottom` must never override an explicit
click. Same test for Startup and the last visible item. Dev gate: show the Developer card and rail item only when
`UpdateService.IsDevBuild || Environment.GetEnvironmentVariable("ORIGIN_DEV") == "1"`; hide, don't delete. On a release build at
startup, if `OfflineTestMode` is true and no harness flag or `ORIGIN_DEV` is present, set it false through `SettingsStore.Update`
(a player who once toggled it would otherwise be stuck). Add a launcher-shots shot `14c-settings-client-first-click`.

**U1.2** Rename the user-facing "Website" to "Store" everywhere a label, tooltip or automation name says it (grep the launcher's XAML and
strings; promo `clickUrl` values are promos, not labels, and stay). One constant `OriginLinks.Store = "https://originclient.org/store"`;
the button calls `ExternalLink.Open(new Uri(OriginLinks.Store))`. `check_consistency.py` gains a check that this constant equals
`STORE_PAGE` in `services/cosmetics/src/stripe.js` (a detector, so the URL can't drift or be invented). The card stays in today's
top-bar slot with a modest restyle in the current tokens ("Store" text, a small glyph from the existing icon set or none, hover; never the generic globe; no
embedded web content); its final placement and look are a Phase 2 proposal and land in Phase 3.

**U1.3** Delete `assistant.html`, `css/assistant.css`, `js/assistant.js`; remove the nav links (`index.html:48`, `store.html:43`); restore the
"Public code / Read every line." card exactly from `311f4fc72^`; revert the phone-nav selector to `:not(.nav-store):not(.nav-home)` with
the comment "Phones keep only the Store link..." as before; remove terms section 10 and set "Last updated" to the date of this change;
grep the repo (excluding `docs/overhaul/`, `MEMORY.md` history, `.claude/` and `CLAUDE.md`, which are Claude tooling, not product) for
`assistant`, `AI helper`, `Origin AI`, `origin-assistant-api`, `anthropic`: 0 product hits; any remaining hit is listed with why it is not an AI feature. Judge loop: design-reviewer scores
the home, store and terms pages at 375 and 1280 px (fun, polish, mobile, accessibility, honest claims) until 9/10; then push the
website-only commits to `main` (S7). After deploy: `https://originclient.org/assistant` returns 404, `/store` and `/` return 200, phone
nav on `/store` shows Home. If `https://github.com/dozeyy/originclient` does not return 200 (private repo), do not publish a dead link:
restore the card without the link and record it (section 9).

## 6. Must NOT change
Settings content and order other than the Developer entry; OfflineTestMode consumers and CLI harness flags; any other launcher page;
the store page, `js/store.js`, `services/cosmetics`, promos.json; website pages other than the listed edits.

## 7. Dependencies
Phase 0 closed (U0.3 changed SettingsPage.xaml/.cs; baseline shots exist). Phase 2 owns the final Store placement.

## 8. Verification
- UiTests: the three first-click rail tests pass, and fail when the fix is reverted (verifier reverts locally once to prove it).
- UiTest or render check: Developer card and rail item absent on a release-configured build, present with `ORIGIN_DEV=1`.
- `dotnet test` count >= Phase 0 baseline; build-check green; `check_consistency.py` passes and fails when `OriginLinks.Store` is edited.
- launcher-shots: `14-settings` without Developer, `14c` shows Client selected, top bar shows "Store"; compare against `p0-baseline`
  for unintended changes (sheet to sheet).
- Website: link resolver 0 missing refs; 0 console errors on `/`, `/store`, `/terms` at 375 and 1280; judge score >= 9 recorded; live
  curl results after deploy.

## 9. Needs Will (held; nothing else waits on it)
- Will says the AI assistant Worker is gone (04). U1.4 confirms read-only: `curl -s -o /dev/null -w "%{http_code}"` on the Worker URL
  (expect no answer / 404). If it still answers, the report says so and asks Will to delete it and revoke its Anthropic key; agents
  never touch Cloudflare, Anthropic or the cosmetics deployment.
- GitHub card: if the repo link is not public (U1.3 curl not 200), the card is restored without the dead link and the report asks Will
  whether to make the repo public. Orchestrator decision, Will can override.

## 9b. Parallel and agents
Track A, first phase after Phase 0. U1.1 (desktop-app-dev), U1.2 (ui-builder) and U1.3 (website-dev) run in parallel in separate
worktrees (U1.1 and U1.2 both touch `SettingsPage`/`MainWindow`: U1.2 rebases on U1.1 before merging). U1.4 (verifier) after.
Judge: `design-reviewer`. Tracks B, C, D may run at the same time (README "Which phases run in parallel").

## 10. Completion
U1.1-U1.3 accepted (S2), website live and verified, phase branch merged into `main` and `main` pushed (README step 5), ORIGIN_PROGRESS
and MEMORY updated, **the judge passes Phase 1 (`docs/overhaul/JUDGE.md`)**, report sent ("You need to do": only the section 9
items that are still open). No release here (S8).
