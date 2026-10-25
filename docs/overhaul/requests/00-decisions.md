# Will's decisions so far (2026-10-25)

Answers Will gave during the overhaul planning, in the order he gave them:

- **In-game main menu look:** "Match launcher softness". The main menu and other non-Mod-Menu surfaces in game get rounded corners and softer surfaces like the launcher. This replaces the old pixel-grid, square-button rule for those surfaces. The in-game Origin Mod Menu does NOT change.
- **Launcher colour:** "Keep soft accents". Neutral base, small soft-hued accents (icon tiles, status dots, badges, progress, selected rings), never big colour fills. The big Home version numeral stays neutral white/grey; Will rejected an accent-tinted numeral.
- **2026-10-20 paused playtest backlog:** "Fold into overhaul". Its open items become tasks inside the overhaul plan.
- **1.8.9 real-GPU benchmarks:** "I run one command". Agents build a scripted benchmark, and Will runs one command on his PC that writes a readable report. Agents never open game or launcher windows on his PC (standing rule).
- **Design direction:** WORKBENCH (concept A), refined, not reinvented. See 02-workbench-refinement.md.
- **Plan and order:** approved. See 03-work-order.md, which is the official phase order.

Still open (ask at Phase 5):
- Should importing another launcher's instances create Origin profiles? This reverses bdcd7dc5a.
- May Fix Compatibility look up missing deps on Modrinth? It would send mod ids and hashes only.

Reference material in the scratchpad (temporary; anything that must survive goes into the repo):
- Audits: ../audit/ (launcher-ui, launcher-systems, ingame-menus, perf-189, website, ledger, architecture)
- Concepts: ../design/concepts.md, concepts.html, view/*.jpg
- Up-to-date origin/main worktree: ../audit-wt (detached at de6b3eb6a)
