# Will's request, verbatim (2026-10-25, third message): THE OFFICIAL ORDER OF WORK

ORIGIN CLIENT — FINAL APPROVED WORK ORDER & EXECUTION RULES
This is the official order of work for the Origin Client overhaul.
Follow this sequence. Do not rearrange the major phases unless a genuine technical dependency makes it necessary. Explain any proposed change before proceeding.
The main Claude conversation is the orchestrator. It coordinates all agents, reviews their work, tracks the original requirements, and ensures the project remains consistent.
Before implementation, prepare a separate, detailed execution prompt for each phase and its individual tasks.
Keep agent instructions detailed, but keep progress reports to me short and simple.

PHASE 0 — CLEANUP
Complete this before beginning the numbered phases.
* Merge the two finished but unmerged fixes:
  * Imported mods must not prevent the game from starting.
  * Launcher self-updates must be verified against their expected hash.
* Verify both fixes actually work.
* Investigate approximately 125 leftover work copies, branches, or worktrees.
* Identify which are genuinely safe to remove.
* Preserve unfinished work and any unique changes.
* Clean up obsolete work copies only after verification.
* Update stale project notes and documentation.
* Establish an accurate project baseline.
Do not delete anything important simply because it appears old.

PHASE 1 — QUICK FIXES
Complete the smaller existing issues before the larger redesign.
* Fix the Settings sidebar bug that incorrectly opens Developers before Origin Client.
* Remove developer mode from public launcher navigation while preserving secure internal development access.
* Remove public AI features from the website.
* Restore the GitHub card.
* Fix website navigation on phones.
* Improve the Origin Store card and its placement.
* Verify the Store opens the correct configured URL.
Ensure these fixes do not break existing functionality.

PHASE 2 — UNIFIED DESIGN SYSTEM
Use the approved design direction as the foundation for the entire Origin ecosystem.
Preserve and refine Workbench unless I approve a different direction.
Create shared standards for: Colors. Typography. Component sizing. Spacing. Corner radii. Buttons. Dropdowns. Navigation. Icons. Hover states. Animations. Backgrounds. Loading indicators.
The launcher and Minecraft main menu must feel like parts of the same product.
Always use the real official Origin logo.
Never use the fake logo again.
The existing in-game Origin Mod Menu is visually approved and must not be redesigned.
Present any remaining major design decisions for approval before implementing them.

PHASE 3 — COMPLETE LAUNCHER REDESIGN
Apply the unified Workbench design system throughout the launcher.
Prioritize:
1. Fixed translucent top navigation bar.
2. Correct numerical typography and centering.
3. Two-step Minecraft version selector.
4. Clear installed and downloadable version states.
5. Play button that handles required installation automatically.
6. Version numbers integrated naturally into card artwork.
7. Correct player-head and account-pill proportions.
8. Better Origin Store card placement and appearance.
9. Consistent buttons, dropdowns, icons, and container sizing.
10. Every remaining launcher screen, dialog, and interaction.
Avoid unnecessary empty space and oversized bars.
Every component should be sized appropriately for its content.
Ensure the installer, setup experience, and uninstaller also follow the approved Workbench design.
Test all relevant screens and interactions.

PHASE 4 — IN-GAME MAIN MENU
Redesign the Minecraft main menu to match the launcher.
Start with Minecraft 1.21.1.
Verify the design and functionality before porting it to other supported Minecraft versions.
Port version by version, respecting differences in Minecraft's menus and available features.
Requirements: Match the Workbench visual identity. Preserve vanilla menu functionality. Use Origin's custom backgrounds. Improve icon styling without unnecessary nested boxes. Preserve version-specific buttons. Make the Mods button open the existing Origin Mod Menu. Maintain appropriate performance.
Never visually redesign the existing Origin Mod Menu.
Also ensure every supported Minecraft version, including 1.8.9, receives the custom Origin loading experience.
The loading screens must use the real Origin logo and remain lightweight.

PHASE 5 — BACK-END SYSTEMS
Build the shared compatibility infrastructure before implementing features that depend on it.
Follow this internal order:
5.1 Shared compatibility checker — Create one reliable compatibility-checking system used by: Profile management. Launch preparation. Imported modpacks. Dependency management. Crash recovery.
5.2 Per-profile mod versions — Support different compatible versions of Origin-managed components for different profiles. Avoid global changes that break other profiles.
5.3 Fix Compatibility — Implement the one-click repair feature. Preserve original profiles and create repaired copies where appropriate.
5.4 Crash Report V3 — Implement automatic crash diagnosis and recovery. Detect likely causes, explain them clearly, and offer safe automatic repairs when supported. Distinguish confirmed causes from guesses.
5.5 Guided import screens — Implement the new import flow: 1. Choose one source launcher. 2. Import Everything or Choose What to Import. 3. Confirm and import. 4. Verify the imported content.
Do not claim unsupported data can be transferred.
Ensure all these systems use the shared compatibility checker rather than duplicating compatibility logic.

PHASE 6 — MINECRAFT 1.8.9 PERFORMANCE
Minecraft 1.8.9 performance is a major priority.
Follow this order:
6.1 Benchmark mode — Implement repeatable performance benchmarking before optimization. Measure average FPS, 1% lows, frame times, memory usage, and other useful metrics.
6.2 Performance presets — Implement and test: Competitive. Balanced. Quality.
6.3 Targeted optimization — Investigate and optimize: HUD rendering. Particles. Cosmetics. Blur effects. Unnecessary rendering overhead. Memory allocation. Other measured bottlenecks. Preserve visual correctness and multiplayer functionality.
6.4 Real GPU testing — Provide one clear command I can run on my own PC to perform the benchmark. The command should produce an understandable results report. Do not fabricate GPU benchmark results or pretend CI performance represents actual gameplay on my hardware. Use the results to identify further optimization opportunities.

PHASE 7 — MOD UPDATES
Audit third-party mod dependencies across supported Minecraft versions.
For each proposed update:
1. Identify the current version.
2. Find the latest compatible stable version.
3. Review compatibility and breaking changes.
4. Update the dependency.
5. Run appropriate build and compatibility tests.
6. Boot-test the affected Minecraft configuration in CI.
7. Verify startup and mod initialization.
8. Reject or hold updates that fail testing.
Do not blindly install the newest versions.
Do not ship an update that fails required checks.
If CI cannot provide a suitable Minecraft boot test, report that limitation and require appropriate verification before shipping.

SEPARATE EXECUTION PROMPTS
Before starting, generate one detailed execution prompt for each phase: Phase 0: Cleanup. Phase 1: Quick fixes. Phase 2: Design system. Phase 3: Launcher redesign. Phase 4: Minecraft main menu. Phase 5: Back-end systems. Phase 6: 1.8.9 performance. Phase 7: Mod updates.
Each prompt must include: Exact objectives. Relevant original requests. Agent assignments. Systems to inspect. Changes to make. Things that must not change. Dependencies. Verification requirements. Completion criteria.
Break complex phases into smaller agent tasks when appropriate.
The main orchestrator remains responsible for coordinating and integrating everything.

SHORT REPORT AFTER EVERY PHASE
After each phase, provide a short progress report using simple language.
Example:
Phase 3/7 — Launcher Redesign
Done
* Fixed top bar.
* Improved version cards.
* Changed Download to Play.
* Fixed account pill.
* Updated launcher screens.
Original fixes
* Version picker: Working.
* Play button: Working.
* Store: Working.
* Logo: Verified.
Still needs work
* 1.8.9 loading test.
You need to do
* Nothing.
Next
* Phase 4: Minecraft main menu.
Keep these reports brief. Use plain English rather than technical jargon. Only mark items as working when they have actually been verified. If something was implemented but not tested, say so.

MASTER REQUIREMENTS CHECKLIST
Maintain a single master checklist covering every original request and all subsequent instructions.
Track each item as: Done and tested. Done but untested. In progress. Not started. Blocked. Needs my decision.
Update it after each phase.
Keep project progress documented so work can resume across Claude Code sessions.
Prefer one existing project tracking file or a single ORIGIN_PROGRESS.md file.
Avoid creating unnecessary duplicate planning documents.

FINAL EXECUTION RULES
1. Follow the approved phase order.
2. Prepare separate detailed prompts before implementation.
3. Use the main chat as the orchestrator.
4. Delegate suitable work to specialized agents.
5. Preserve the approved Workbench direction.
6. Never use the fake Origin logo.
7. Never visually redesign the existing in-game Origin Mod Menu.
8. Verify every completed task.
9. Preserve user data and unfinished work.
10. Give me a short progress report after every phase.
11. Tell me exactly what you need me to do.
12. Keep the original requirements checklist updated.
13. Do not silently skip difficult work.
14. Continue to the next approved phase when no user decision is needed.
15. Stop for approval before major unapproved design changes, destructive actions, or significant scope changes.
Start with Phase 0 — Cleanup. First inspect the existing state, identify the two pending fixes and leftover work copies, and present the cleanup plan before performing any potentially destructive actions.
