# Will's request (2026-10-25, first message): ORIGIN CLIENT — COMPLETE PRODUCT OVERHAUL
(The wording is Will's. Lists were compacted onto single lines and sections 20-23 were lightly condensed; no requirement was dropped.)

(Later messages win where they conflict: 02-workbench-refinement.md and 03-work-order.md, plus 00-decisions.md.)

You are the lead engineering orchestrator for Origin Client, a premium Minecraft launcher and game client designed to compete with products such as Lunar Client and Badlion Client.
Your task is to coordinate a comprehensive overhaul covering launcher design, Minecraft main menus, performance, compatibility, mod management, importing, crash recovery, website cleanup, and outstanding development work.
The objective is to create a highly polished, sophisticated, extremely performant Minecraft ecosystem that feels like one unified product.
This is a serious, production-quality development initiative. Do not treat it as a collection of small cosmetic fixes.
However, do not unnecessarily rewrite working systems, discard existing features, or make changes simply for the sake of changing things.

# 1. CRITICAL INSTRUCTIONS — READ FIRST
### 1.1 Preserve the existing Origin Mod Menu
DO NOT REDESIGN THE EXISTING IN-GAME ORIGIN MOD MENU.
I am happy with its current appearance, layout, styling, and overall user experience.
Preserve: Existing layout and navigation. Visual appearance. Typography and spacing. Colors and component styling. Animations. Mod configuration interfaces. Existing working functionality.
You may fix actual bugs, improve performance, update compatibility, and make necessary internal architectural improvements.
However, do not visually redesign it or replace it with a new interface.
The new unified design system should take the existing Mod Menu into consideration, but must not force visual changes onto it.
The Mod Menu is an approved part of Origin's identity.
### 1.2 Do not begin implementation immediately
First inspect the project, investigate ongoing work, review previous notes, and prepare a complete plan. Present the plan and design proposals to me before making changes. WAIT FOR MY APPROVAL BEFORE IMPLEMENTATION.
### 1.3 Preserve working functionality
Do not remove, replace, or break working features while redesigning the product.
### 1.4 Verify everything
Do not claim something is fixed because code was written. Test it. Do not fabricate benchmark results, compatibility claims, completed tasks, or successful tests.
### 1.5 Prioritize quality over speed
Use careful engineering, proper architecture, thorough investigation, and meaningful verification. Avoid rushed patches and unnecessary complexity.

# 2. MULTI-AGENT DEVELOPMENT SYSTEM
The main Claude conversation must act as the central orchestrator for the entire project: planning, coordination, design consistency, decision-making, integration, quality assurance, and communication with me. Specialized agents perform research, implementation, analysis, and testing within clearly defined responsibilities. Do not allow multiple agents to independently redesign or modify overlapping systems without coordination.
Suggested agent responsibilities:
- Agent 1 — Launcher UI/UX and Design System: auditing launcher interfaces; designing the unified visual system; launcher navigation; home screen; settings screens; version selector; profiles; store card; dialogs, modals, overlays; typography, spacing, animations; responsive layouts.
- Agent 2 — Minecraft Main Menu and Version UI: main-menu design proposals; version-specific menu differences; vanilla menu functionality; custom backgrounds; main-menu navigation; visual consistency with the launcher; main-menu-to-Mod-Menu navigation. Must preserve the existing Origin Mod Menu appearance.
- Agent 3 — Minecraft Performance Engineering: 1.8.9 optimization; CPU and GPU bottlenecks; rendering efficiency; memory management; Java runtime evaluation; frame-time consistency; low-end hardware performance; benchmarking.
- Agent 4 — Mod Compatibility and Dependencies: third-party mod updates; Sodium and other dependencies; Minecraft version compatibility; loader compatibility; Origin-managed mod versions; dependency resolution; cross-version architecture.
- Agent 5 — Import System and Profile Management: launcher import redesign; source launcher detection; import validation; profile creation; modpack compatibility; one-click repair; preserving original imported data.
- Agent 6 — Crash Diagnosis and Recovery: crash detection; crash-report parsing; log analysis; dependency conflict identification; Java errors; mod initialization failures; automatic repair recommendations; safe recovery procedures.
- Agent 7 — Website and Integration: removing public AI features; website cleanup; store integration; broken links; website build verification; launcher-to-website integration.
- Agent 8 — Quality Assurance and Regression Testing: test planning; build verification; regression testing; cross-version testing; UI testing; performance comparisons; reporting remaining issues.
These are responsibility areas, not a requirement to run eight agents simultaneously.
Orchestration rules — the main orchestrator must: 1. Inspect the repository and define the overall plan. 2. Delegate independent tasks to appropriate agents. 3. Give each agent a precise scope. 4. Establish shared design and architectural requirements. 5. Prevent conflicting file modifications. 6. Track dependencies between tasks. 7. Review agent findings and implementation proposals. 8. Integrate changes only after appropriate review. 9. Coordinate testing. 10. Maintain one authoritative project status report.
Use separate branches or isolated worktrees where beneficial. Do not allow agents to overwrite one another's changes. Agents report findings to the orchestrator, not independently make major product decisions. Avoid spawning agents for trivial tasks or unnecessary coordination overhead.

# 3. COMPLETE PROJECT AUDIT
Before changing any code, investigate thoroughly: README files, documentation, TODO lists, development notes, existing plans, relevant Git history, current uncommitted changes, open or unfinished work, existing tests, build configurations, dependency manifests, supported Minecraft version configurations.
Identify every previously requested fix that can be found in the available project records. Inspect the actual implementations and classify each item as: Completed and verified. Implemented but unverified. Partially implemented. Broken. Not implemented. Blocked. No longer applicable.
Do not delete unfinished work without understanding its purpose. Produce an initial audit report.

# 4. COMPLETE LAUNCHER UI/UX REDESIGN
Redesign the entire Origin launcher to feel more sophisticated, refined, modern, and premium. The current launcher feels too heavily gaming-oriented. Not a generic esports launcher filled with neon, glowing borders, and exaggerated gaming effects; also not a boring corporate productivity application.
Desired aesthetic: Premium desktop software meets Minecraft gaming culture. Mature and sophisticated while still recognizably Minecraft-inspired.
Aim for: clean and balanced layouts; sophisticated typography; restrained accent colors; subtle Minecraft-inspired details; tasteful use of custom imagery; consistent rounded corners; thoughtful spacing; high-quality navigation; smooth, subtle animations; carefully controlled translucency; clear visual hierarchy; distinctive Origin branding.
Avoid: excessive neon; random gradients; constant glowing effects; overly large buttons; oversized icons; excessive glassmorphism; generic AI-generated dashboard designs; unnecessary visual clutter; aggressive esports styling; inconsistent component designs.
Redesign every launcher interface: Home. Mods section in the desktop launcher. Profiles. Settings and all subsections. Account management. Version selection. Version installation. Downloads and updates. Store. Import. Profile creation and editing. Loading and launch states. Error dialogs. Notifications. Confirmation dialogs. Compatibility repair. Crash recovery. Dropdowns and context menus. All other launcher interfaces discovered during the audit.
The desktop launcher's Mods section is included in the redesign. The protected in-game Origin Mod Menu is not.
Do not remove functionality to achieve a cleaner appearance. Reorganize features intelligently.

# 5. UNIFIED ORIGIN DESIGN SYSTEM
Create a cohesive design system connecting the desktop launcher and Minecraft in-game interfaces. The launcher and Minecraft main menu should feel like the same product. They should share: typography principles, color palette, button styling, spacing standards, corner radii, border treatments, navigation hierarchy, animation timing, hover behavior, selected states, iconography, background styling, loading indicators, overall visual personality.
Define reusable design tokens and components where appropriate. Because the launcher and Minecraft client use different rendering technologies, do not force them to share runtime UI dependencies. Share a documented visual specification and compatible assets where technically appropriate.
Important exception: the existing in-game Origin Mod Menu is visually approved. Do not change its appearance to match a new design system; consider its established visual identity when creating the new launcher and main menu.
Launcher-to-game continuity: Origin Launcher → Launching State → Minecraft Loading → Origin Main Menu should feel natural (consistent branding, shared typography, related backgrounds, similar button geometry, coordinated motion, consistent accent colors). Avoid expensive transitions that delay startup or hurt performance.

# 6. LAUNCHER NUMBERS AND ALIGNMENT
The numerical elements displayed in the launcher look compressed or squashed. Fix the typography: correct character proportions, no horizontal stretching, proper letter and number spacing, appropriate font weight, consistent line height, sharp rendering, correct scaling.
The numbers must be centered horizontally and vertically in the available area between the Home/Mods/Profiles/Settings navigation bar and the top boundary of the section containing Minecraft version buttons such as 1.21.1. Use proper layout constraints rather than arbitrary pixel offsets. Verify at multiple window sizes.

# 7. LAUNCHER TOP NAVIGATION BAR
Redesign the top navigation bar to remain clearly visible while scrolling. Distinct translucent surface with appropriate opacity, subtle background blur where practical, a restrained bottom border or shadow, fixed positioning, clear labels, consistent spacing, readable text, smooth interaction states. Scrolling content moves behind it without becoming visually confusing. Avoid excessive glass effects.

# 8. COMPLETE VERSION SELECTOR REDESIGN
Replace the current version selector with a spacious, responsive grid. Primary selection: large rectangular cards with subtly rounded corners, each a major Minecraft version family (e.g. 1.8, 1.12, 1.16, 1.20, 1.21) — only families actually supported by Origin. Use the available screen area effectively; avoid tiny cards or unnecessary empty space.
Secondary selection: clicking a family opens a separate selection interface showing its supported specific releases. Flow: Major version → Specific release → Select or launch.
Requirements: clearly distinguish installed and downloadable versions; accurate compatibility information; preserve the selected version; smooth, subtle transitions; keyboard navigation; handle errors gracefully; avoid misleading support claims.

# 9. SETTINGS NAVIGATION BUG
Clicking Origin Client for the first time in the Settings sidebar incorrectly opens Developers; clicking a second time opens Origin Client correctly. Investigate the actual root cause (routing, state initialization, navigation handlers, tab indices, component synchronization). Fix the underlying issue rather than adding a workaround.
Remove public developer mode: remove developer mode from normal user-facing navigation; do not expose developer-only controls to regular users; retain legitimate internal development tooling through appropriately secured development workflows; do not rely on hidden buttons or secret UI interactions for security.

# 10. STORE CARD REDESIGN
Replace the current Website button with a visually appealing Store card. Rename appropriate user-facing references from Website to Store. The card must link to the actual configured Origin Store; do not invent a URL. Consider a subtle store preview background, tasteful imagery, a restrained dark overlay, the text "Store", rounded corners, subtle hover effects, clean typography, a premium presentation. No generic website icon. Creative freedom; keep it simple; avoid expensive embedded web content.

# 11. COMPLETE IMPORT SYSTEM REDESIGN
Step 1 — Select source launcher: show only sources Origin can genuinely import from; exactly one source per import.
Step 2 — Choose import scope: "Import Everything" (all detected supported transferable content) or "Choose What to Import" (individual categories and items: profiles, instances, mods, mod configurations, resource packs, shader packs, settings, keybinds, other supported local content). Do not claim proprietary account data, cosmetics, or unsupported configurations can be imported.
Step 3 — Execute import: automatic source detection where possible; manual folder selection when needed; accurate file discovery; integrity checks; duplicate detection; preservation of original files; safe profile creation; correct folder structure; error handling; progress reporting; import verification. Validate imported content instead of assuming copied files will work. Generate a completion report showing successful, skipped, unsupported, and failed items.

# 12. ONE-CLICK MODPACK COMPATIBILITY REPAIR
Version-aware compatibility system. When imported mods conflict with Origin-managed components, detect the problem before launching where possible. Inspect Minecraft version, mod loader, mod versions, dependency requirements, version constraints, Origin-managed component versions, Java requirements, known incompatibilities.
"Fix Compatibility" button, shown when a supported repair is available. After user confirmation: 1. Preserve the original profile. 2. Create a new compatible profile. 3. Retain the imported mods. 4. Select compatible versions of Origin-managed components. 5. Install required trusted dependencies. 6. Apply necessary configuration changes. 7. Validate the resulting setup. 8. Save the new profile. 9. Make it available for launching.
Different profiles should be able to use different compatible versions of Origin components where technically feasible. Never globally replace dependencies in a way that breaks other profiles. Never delete the original modpack. If no compatible solution exists, explain the conflict clearly. Do not pretend every modpack can be repaired.

# 13. AUTOMATIC CRASH DIAGNOSIS AND RECOVERY
A major Origin feature. When Minecraft crashes, fails to launch, or exits unexpectedly, Origin helps identify the likely cause and provides actionable solutions without the user searching logs.
Crash detection: detect abnormal termination and inspect Minecraft version, loader and version, Java runtime, exit code, crash reports, launcher logs, mod initialization logs, dependency errors, stack traces, memory allocation, relevant configuration, recent profile changes. Distinguish actual crashes from intentional closure. Do not misclassify every nonzero exit code as a specific known issue.
Diagnosis engine: identify missing dependencies, incompatible mod versions, wrong Minecraft version, incorrect loader, unsupported Java runtime, mod initialization failures, invalid configurations, corrupted files, memory allocation failures, known rendering conflicts, unsupported graphics features, broken resource packs or shaders, native library errors. Use deterministic rules, metadata, known compatibility information, and actual log evidence. Do not simply search logs for generic words such as "error" and guess.
Crash recovery interface (polished Origin crash report screen): What happened. Likely cause. Recommended fix. Fix Automatically (only when a safe supported repair exists). View Technical Details.
Automatic repairs where feasible: installing missing dependencies; selecting compatible mod versions; correcting Java runtime selection; repairing corrupted managed files; restoring safe configuration defaults; creating a repaired profile; resolving known version conflicts. Never silently delete user mods or overwrite important configurations. Create backups or new profiles before potentially destructive repairs. Require confirmation for changes affecting user data.
Diagnosis confidence: Confirmed cause / Strongly suspected / Possible / Unknown. Do not present guesses as facts. If the cause can't be determined, say so and provide useful technical information.
Privacy: work locally; never automatically upload logs, account information, access tokens, or private file paths. If uploads are ever supported, require explicit consent and sanitize.
Testing: test cases for common crash categories; verify diagnosis accuracy and repair behavior; avoid false positives and destructive repairs.

# 14. THIRD-PARTY MOD UPDATES
Audit all third-party mods and dependencies (Sodium and other rendering, performance, utility, compatibility components). For each: installed version; latest compatible stable version; supported MC versions; loader compatibility; breaking API changes; conflicts with Origin modifications; whether upgrading is beneficial; test the upgrade. Do not blindly update. Maintain version-specific dependency sets. Preserve working Origin functionality. Document dependencies that cannot safely be upgraded.

# 15. MINECRAFT 1.8.9 — EXTREME PERFORMANCE PRIORITY
Top engineering priority: excellent FPS, smooth frame pacing, low input latency, minimal memory overhead, stability; run exceptionally well on weak hardware without unnecessarily limiting high-end systems.
Performance audit: rendering overhead, CPU usage, draw calls, chunk rendering, chunk updates, entity rendering, particle rendering, texture management, HUD rendering, event dispatch, memory allocation, garbage collection, mod initialization, world loading, startup time, background processes. Find actual bottlenecks before making changes.
Optimization: efficient rendering paths, reduced GPU state changes, buffer reuse, reduced per-frame allocations, better entity visibility handling, efficient particle rendering, optimized chunk updates, lightweight HUD processing, improved resource loading, appropriate threading, reduced background activity. No rendering artifacts, broken mechanics, or unstable optimizations.
Java runtime: investigate the best runtime for 1.8.9; don't assume newer is faster; evaluate legacy MC compatibility, LWJGL compatibility, mod compatibility, JVM behavior, GC, stability, startup time, actual benchmark results. Use the most appropriate tested runtime; support version-specific Java selection.
Presets (optional): Competitive (FPS, low latency, frame pacing, minimal overhead), Balanced, Quality. Respect version capabilities; do not silently override user preferences.
Benchmarking: average FPS, 1% low FPS, frame-time consistency, memory usage, CPU usage, startup time, input responsiveness where measurable. Scenarios: multiplayer PvP, entity-heavy, particle-heavy, chunk loading, low-end settings, high render distances. Repeatable conditions; compare against current Origin. Never fabricate numbers or claim hardware testing that was not performed.

# 16. MINECRAFT MAIN MENU REDESIGN
Redesign Minecraft's main menus to match the new Origin launcher; sophisticated, premium, Minecraft-inspired, consistent with Origin's identity.
Preserve vanilla functionality per version: Singleplayer, Multiplayer, Realms where supported, Options, Language, Accessibility where supported, Quit Game, other relevant version-specific actions. Use Origin's custom backgrounds where appropriate. Do not display unsupported version-specific functionality.
Origin Mods button: the main menu must include a Mods button that opens the existing Origin Mod Menu (do not redesign it). If a list of third-party installed mods is needed, add it without altering the approved Mod Menu design. Do not introduce unsupported third-party mod toggles or allow users to disable critical dependencies.
Design approval: present at least three distinct concepts showing how launcher and main menu connect (layout, typography, button styling, background, navigation, animation, version-specific behavior, relationship to the Mod Menu); recommend one; wait for approval. [DONE: Workbench chosen.]

# 17. WEBSITE — REMOVE PUBLIC AI FEATURES
Remove AI assistants, AI chat interfaces, AI navigation entries, AI marketing sections, AI-specific pages, AI-related UI text, API integrations used exclusively by removed AI features, unused AI dependencies. Ensure removed features are no longer accessible through public routes. Do not remove unrelated development tooling or break shared website functionality. Verify the website still builds. Check for broken layouts, dead links, unused code.

# 18. CROSS-VERSION ARCHITECTURE
Review shared launcher systems, version-specific game modules, version adapters, profile isolation, dependency resolution, Java runtime management, shared configuration, version-specific performance optimizations, consistent UI specifications. Do not force incompatible versions to share internals; 1.8.9 and modern versions may need different rendering strategies, runtimes, and mod integrations. Share where beneficial, specialize where necessary.

# 19. COMPLETE QUALITY ASSURANCE
Launcher: startup, navigation, settings, version selection, profiles, importing, store navigation, downloads, compatibility repair, crash recovery, window resizing, scrolling, keyboard interaction, error states.
Minecraft: version-specific startup, main-menu navigation, singleplayer, multiplayer, Origin Mod Menu access, existing Origin mod functionality, third-party mod compatibility, resource packs, shaders where supported, rendering, input, shutdown and relaunch.
Website: build success, navigation, store links, removed AI routes, responsive layouts, console errors.
Regression testing: add automated tests where practical; use existing infrastructure; document manual tests where automation is unavailable. Never claim something was tested unless the test actually ran.

# 20-21. EXECUTION + STANDARDS
Phases: audit → design proposals → architecture/implementation plan → STOP for approval → implementation in manageable groups (isolated branches/worktrees, orchestrator reviews before integration) → testing and integration → final verification against every original requirement with a final report (completed features, fixed bugs, UI changes, performance changes, compatibility improvements, updated dependencies, crash diagnosis capabilities, import improvements, test results, remaining limitations, next steps).
Standards: understand before modifying; preserve working systems; fix root causes; avoid unnecessary rewrites; readable maintainable code; avoid unnecessary dependencies; preserve user data; keep interfaces responsive; low overhead; respect version-specific behavior; trustworthy dependency sources; validate downloads; handle failures gracefully; keep changes reversible; avoid generic AI-looking UI; consistent visual design; test actual behavior; report uncertainty; never fabricate results; keep the orchestrator informed; preserve the approved Mod Menu design; no major design changes before approval.

# 22. ADDITIONAL RECOMMENDATIONS
Proactively investigate: faster launcher startup, faster Minecraft startup, better download reliability, automatic integrity verification, improved profile management, smarter compatibility checks, better crash recovery, improved performance presets, reduced memory usage, better low-end support, cleaner settings organization, more intuitive navigation, more efficient resource loading, better version-specific defaults, improved error messaging, more reliable imported modpacks. Do not automatically implement major additional features — present them first with benefits, risks, complexity.

# 23. FINAL PRODUCT VISION
Origin should feel like a complete premium Minecraft ecosystem, not a launcher with a separate mod attached. Sophisticated launcher; main menu as its natural extension; Mod Menu keeps its approved design; cohesive experience; 1.8.9 exceptionally optimized for competitive play and low-end hardware; modern versions get reliable dependency management, updated compatible mods, strong performance; simple importing; understandable, repairable crashes; every feature intentional, every screen polished, every supported version stable. Not simply Lunar-alike: a distinctive, technically excellent client.
