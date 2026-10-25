# Will's overnight authorization (2026-10-25, fourth message) — condensed faithfully; rubric and rules verbatim

Will is asleep. Autonomous completion of the approved work order (03-work-order.md), phases 0-7, without asking
about small decisions. Goal: "a version of Origin Client that I would genuinely approve, not merely one that
technically passes a build." "Do not stop at 'it works.' Aim for 'it works beautifully, consistently, reliably,
and efficiently.'" Don't restart completed phases or overwrite working changes. Proceed between phases without
approval when within scope.

## Will's answers to the pre-sleep questions (2026-10-25)
- LOGO: the official logo IS `textures/ui/origin_logo.png` (three tilted rings, 256x256; same art as
  `Assets/origin.ico`) and the wordmark IS `src/OriginLauncher.App/Assets/Brand/wordmark.png`. Use those exact
  files everywhere, never redrawn. Replace every hand-drawn / XAML / SVG / code recreation (OriginMark.xaml,
  StartupIntro.xaml rings, OrbitMark.xaml, website inline symbols + world.js:396-425, OriginUi.mark(), Mark B in
  ModIcons.cs:81-91) with the real files. Centralize the reference. Fix the "Mod ID" placeholder icon.png in
  1.21.10, 1.12.2, 1.21.5 and shared.
- WORKER: only the AI assistant Worker (`origin-assistant-api`) is gone. The cosmetics service
  (services/cosmetics: store, owned capes, friends/social) stays and keeps working as now. Remove every AI
  assistant reference; do not touch the cosmetics service's production deployment.
- PC TESTING: "My real install too" — agents may run Will's installed Origin launcher with his accounts and
  launch Minecraft on his PC. Never delete worlds, saves, profiles or accounts; back up anything before changing
  it; never change credentials.
- RELEASE: "Yes, if it all passes" — tag one launcher release (launcher-v1.0.66+, `git tag launcher-v1.0.N
  origin/main && git push origin launcher-v1.0.N`) once tests, CI boots and the judge all pass.

## Agents (Will's list)
Launcher UI/Workbench · main menus + loading screens · compatibility architecture · importing + profiles ·
crash diagnosis · 1.8.9 performance · mod dependency updates · testing/regression. Parallel where independent,
no conflicting modifications (worktrees). Orchestrator integrates and keeps the master checklist.

## Independent judge (verbatim rubric)
Judge is never the implementing agent. Inspects real code, screenshots, test results, rendered UI; never trusts
the implementer's description. Score each completed phase 0-100:
| Visual quality and sophistication | 25% |
| Consistency with my preferences | 20% |
| Functionality and correctness | 25% |
| Performance and reliability | 15% |
| Integration and attention to detail | 15% |
Backend-only phases: redistribute visual weight toward correctness, reliability, maintainability.
PASS = 95/100 or higher AND all critical requirements pass (a high average can't compensate for a broken
critical feature). AUTO-REJECT if it: uses a fake Origin logo; visually changes the approved in-game Mod Menu;
introduces generic AI-looking UI; has inconsistent button or dropdown sizing; contains excessive empty space;
breaks existing features; misrepresents compatibility; introduces avoidable performance regressions; contains
untested critical functionality; claims success without evidence; ignores an original requirement.
Loop: implement -> tests/builds -> judge scores + problems -> back to the agent -> re-judge, until pass or a
genuine blocker. Never lower the threshold, never fabricate screenshots/benchmarks/tests. A judge score is an
estimate, not Will's approval.

## Design (non-negotiable)
Workbench, refined. Sophisticated, premium, modern, Minecraft-inspired, not gamer-y. Consistent sizing,
balanced spacing, purposeful buttons, cohesive dropdowns, proper typography, subtle animation, excellent
alignment, compact content-fit containers, consistent icons, clean hierarchy. Avoid excessive neon, glow,
gradients, oversized cards, decorative boxes. Launcher, installer, uninstaller, loading screens, main menus
share one identity. NEVER visually redesign the in-game Origin Mod Menu.

## Compatibility (section 6)
Don't abandon features because the first approach is impossible: find the actual limit, compare alternatives,
pick the simplest safe one, implement, test, document limits. No fragile hacks. Esp. Sodium/Iris.
One user-facing profile, multiple compatible dependency configurations: MC-version mod sets, loader deps,
compatible Sodium/Iris combos, per-configuration Origin components, automatic selection, safe switching,
isolation of incompatible deps (separate internal dirs / linked configs if one mods dir can't). Hidden from
users. Never force incompatible mods into one runtime. Expected: pick profile -> Play -> Origin picks the
configuration; if repair needed, one-click fix. Preserve original profiles and imported data. Never promise
compatibility that doesn't exist. (=> importing creates Origin profiles: yes. Fix Compatibility may query
Modrinth with mod ids/hashes only: yes — no logs, accounts, tokens or private paths uploaded.)

## Performance
1.8.9 is a major priority. Benchmark before optimizing: avg FPS, 1% lows, frame-time consistency, input
latency, memory. HUD, particles, cosmetics, blur, background work. Competitive / Balanced / Quality presets.
No stability sacrificed for FPS claims. Real GPU: use Will's hardware where access works; else one command.
Never pretend CI perf = real gameplay. Mod updates: verify compat and boot-test before shipping.

## May decide alone / must not
May: spacing, sizing, layout, animation timing, refactors, safe bug fixes, tests, dependency resolution,
non-destructive compat work, details within the approved design.
Must not: delete important user data, change credentials, spend money, deploy production infrastructure,
remove important features, replace the design direction, change the Mod Menu look, irreversible ops without
authorization. If blocked, continue other independent work.

## Reports
After every phase: short report (Done / Original requests / Judge score / Needs testing / You need to do /
Next). Full technical detail in project docs (ORIGIN_PROGRESS.md). Final overnight report: completed phases,
judge scores, requests done, verified features, remaining issues, blocked items, real benchmark results,
what Will must test, decisions needed. Plain English.
