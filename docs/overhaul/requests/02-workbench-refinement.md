# Will's request, verbatim (2026-10-25, second message)

ORIGIN CLIENT — WORKBENCH DESIGN REFINEMENT & VISUAL CONSISTENCY
I like the new Workbench design direction. Do not replace it, start over, or introduce an entirely different visual style. The foundation is good, but it needs another serious refinement pass to feel like a cohesive, premium, production-ready Minecraft launcher.
Your task is to carefully inspect the current implementation, identify inconsistencies, and refine the entire experience with exceptional attention to detail.
The goal is to make every component feel intentionally designed as part of one unified system. Nothing should look randomly sized, awkwardly positioned, unnecessarily large, or disconnected from the rest of the interface.
Preserve the Workbench design language, but elevate its execution significantly.

1. CRITICAL — ALWAYS USE THE REAL ORIGIN LOGO
This is non-negotiable.
The Origin logo must always be our actual, official logo from the project's existing assets.
Never use the fake, recreated, approximated, or placeholder Origin logo again.
* Locate and identify the official Origin logo assets in the project.
* Use the correct official logo throughout the launcher, installation setup, uninstaller, loading screens, and other Origin-branded interfaces.
* Remove references to incorrect or placeholder logos.
* Never recreate the logo with CSS, text, generated imagery, or approximated vector shapes.
* Preserve its original proportions, appearance, and transparency.
* Ensure correct scaling without stretching, distortion, or clipping.
* Use appropriate official variants where they already exist.
If multiple logo assets exist and their authenticity is unclear, identify the ambiguity and ask me before replacing anything.
Create a centralized asset reference or shared component so the wrong logo cannot accidentally appear in future interfaces.

2. REFINE THE ENTIRE WORKBENCH DESIGN SYSTEM
Every interface element must feel like it belongs to the same product.
Inspect all: Buttons. Dropdowns. Navigation bars. Cards. Pills. Input fields. Toggles. Version selectors. Profile selectors. Tooltips. Modals. Notifications. Icons. Loading indicators. Hover and selected states.
Establish consistent rules for their sizing, proportions, typography, spacing, corner radii, borders, and interactions.
Do not make everything identical. Components have different purposes and should be sized appropriately. However, they must share recognizable visual principles.
For example, a compact dropdown and a primary Play button can have different dimensions while still feeling like they were designed together.
Sizing and spacing
The current sizing needs refinement.
Audit the interface for oversized containers, excessive padding, unnecessary whitespace, and inconsistent component heights.
A component should only occupy the space its content and purpose require.
Do not make a bar unnecessarily wide or tall simply to fill available space.
* Use content-driven widths where appropriate.
* Use consistent, deliberate padding.
* Avoid oversized pills containing very little information.
* Keep controls visually balanced.
* Maintain comfortable clickable targets.
* Do not sacrifice usability just to make elements smaller.
* Avoid arbitrary fixed dimensions that break at different window sizes.
* Use responsive constraints rather than excessive empty space.
The interface should feel compact, efficient, and sophisticated without becoming cramped.
Purposeful interactions
Every button, dropdown, and interactive element should communicate its function clearly.
Ensure consistent: Hover feedback. Pressed states. Selected states. Disabled states. Focus indicators. Dropdown positioning. Animation duration. Cursor behavior. Keyboard navigation.
Nothing should look clickable if it isn't, and nothing clickable should feel unresponsive.
Avoid decorative controls that serve no purpose.

3. VERSION CARDS — INTEGRATE THE VERSION NUMBER INTO THE IMAGE
The current large version number, such as 36.3, feels disconnected from the card artwork.
I want it to feel like an intentional part of the image composition.
Apply this principle consistently across all version cards, regardless of their version number.
Investigate the current image treatment, typography, overlays, and positioning.
Refine the presentation so the number feels integrated into the artwork rather than like text randomly placed on top.
Consider: Better positioning within the composition. Appropriate typography and scale. More deliberate contrast. Subtle overlays or gradients where needed. Consistent placement rules. Better visual relationships between artwork and text. Responsive behavior across different card dimensions.
Do not distort the image or obscure important artwork.
Do not bake numbers into the source artwork unless there is a clear technical or visual reason.
The number should remain sharp, readable, and maintainable.
Ensure the treatment works for short and long version labels.

4. REPLACE DOWNLOAD VERSION WITH PLAY
The primary version action should be Play, not Download Version.
Origin should handle the installation process naturally as part of the launch flow.
Expected behavior:
1. The user selects a supported Minecraft version.
2. The primary action displays Play.
3. If the version is already installed, Origin launches it.
4. If required files are missing, Origin automatically performs the necessary installation or preparation steps.
5. Show clear progress and status during preparation.
6. Launch the game once everything is ready.
Do not repeatedly ask users to perform unnecessary manual installation steps.
If a download, license agreement, permission, or other required confirmation needs user interaction, handle it appropriately.
Do not label something Play if clicking it does nothing or silently fails.
Ensure the behavior works consistently across all supported versions, including 1.8.9.

5. PLAYER HEAD AND ACCOUNT PILL
The player-head pill currently looks awkward.
The Minecraft head does not sit naturally inside the pill, and the shape of the container does not complement its contents.
Redesign the proportions and internal layout.
The Minecraft head should look intentional and properly integrated.
Consider: The square nature of Minecraft player heads. Appropriate corner radii. Balanced internal padding. Correct vertical alignment. Proper spacing between the head and account information. Consistent scaling. Avoiding clipping or distortion.
Do not force a square Minecraft head into an unsuitable container shape.
If a compact rounded rectangle works better than a fully rounded pill, use it.
The surrounding container should complement the content rather than fight against it.
Apply the same principle to other components throughout the launcher.
Design the container around its content, not the content around an arbitrary container.

6. ORIGIN STORE BUTTON — PLACEMENT AND VISUAL REDESIGN
The current Origin Store button needs better placement and a more polished presentation.
Review its relationship to the rest of the launcher.
Determine where it naturally belongs in the Workbench layout.
It should be easy to discover without competing with the primary Play action.
Refine: Placement. Size. Typography. Background. Visual hierarchy. Hover behavior. Relationship to nearby controls.
Avoid making it look like a generic promotional advertisement or an oversized secondary feature.
It should feel like a natural part of the launcher.
You have creative freedom to improve the appearance, but stay within the Workbench design language.
Use the actual Origin Store destination configured in the project.

7. MAIN MENU ICONS — REMOVE THE UNNECESSARY BOXES
I dislike how the current main-menu icons are each placed inside another box.
The extra containers make the interface feel unnecessarily layered and cluttered.
Redesign the icon presentation.
Explore ways to make the icons feel naturally integrated into their buttons or navigation areas without requiring an additional decorative box around each one.
Possible approaches include: Clean standalone icons. Icons aligned directly with labels. Subtle hover treatments. More integrated button compositions. Consistent icon sizing and spacing.
Do not remove useful visual feedback or clickable areas.
The goal is to remove unnecessary visual nesting, not make the interface harder to use.
Use consistent icon styling and proportions.
Ensure the icons complement the Workbench design.
Do not visually redesign the existing in-game Origin Mod Menu. That interface is already approved and must remain unchanged.

8. INSTALLER, SETUP, AND UNINSTALLER — MATCH WORKBENCH
The Workbench design language must extend beyond the main launcher.
Review and refine the entire installation and removal experience.
This includes: Initial installation setup. Installation location selection. Installation progress. Completion screens. Update setup where applicable. Uninstallation confirmation. Uninstallation progress. Completion and error states.
These interfaces should feel like official parts of Origin rather than generic Windows utility dialogs, where the existing installer technology allows appropriate customization.
Use: The real Origin logo. Consistent typography. Matching colors. Appropriate corner radii. Cohesive buttons. Consistent spacing. Clear progress indicators. Purposeful navigation.
Keep these interfaces simple and efficient.
Do not add unnecessary setup steps or decorative panels.
Preserve correct installation and uninstallation behavior, including shortcuts, file cleanup, user-data preservation choices, and error handling.
Do not delete user profiles, saves, or other personal data without clear authorization.

9. CUSTOM MINECRAFT LOADING SCREEN — EVERY SUPPORTED VERSION
Every supported Minecraft version must use Origin's custom loading experience, including Minecraft 1.8.9.
Audit the current loading-screen implementations and identify which versions are missing the custom experience.
The transition from launcher to game should feel cohesive:
Origin Workbench → Play → Origin Loading → Minecraft Main Menu.
Requirements: Use the real Origin logo. Match the Workbench visual identity. Keep typography and colors consistent. Use clean progress indicators. Avoid unnecessary visual clutter. Keep animations lightweight. Preserve fast startup. Display meaningful loading states where technically available. Avoid fake progress percentages. Handle errors appropriately.
Minecraft 1.8.9
Pay special attention to 1.8.9.
It must have the custom Origin loading experience without sacrificing performance, stability, or compatibility with its older rendering architecture.
Do not assume modern loading-screen code can be directly reused in 1.8.9.
Use version-specific implementations where necessary while maintaining the same visual identity.
Ensure the loading screen does not introduce significant CPU, GPU, or memory overhead.
The loading experience should feel premium, but performance always takes priority over unnecessary animation.

10. COMPLETE VISUAL SANITY CHECK
After implementing the approved refinements, perform a thorough visual and functional sanity check.
Do not stop after confirming that the application builds.
Inspect the actual rendered interface wherever possible.
Review every relevant launcher screen and state.
Check for: Incorrect logos. Inconsistent button heights. Awkward dropdown dimensions. Excessive padding. Misaligned text. Incorrect icon sizing. Uneven spacing. Distorted player heads. Poorly positioned version numbers. Unnecessary nested containers. Oversized navigation bars. Inconsistent corner radii. Clipped text. Overlapping elements. Unbalanced cards. Weak contrast. Inconsistent hover states. Broken responsive layouts. Awkward loading screens. Mismatched installer or uninstaller styling.
Test different launcher window sizes and display scaling settings.
Check normal, hover, selected, disabled, loading, empty, success, and error states where applicable.
Functional sanity check — verify that: Every Play button performs the correct action. Missing versions install or prepare correctly. Every dropdown opens and selects properly. Navigation works on the first click. The Store button opens the correct destination. Account controls display correctly. Installation setup works. Uninstallation works safely. Custom loading screens function across supported versions. Minecraft 1.8.9 loads correctly. The existing in-game Origin Mod Menu remains visually unchanged and functional.
Where screenshot comparison or visual testing tools are available, use them.
Do not claim visual verification if the interfaces were not actually rendered and inspected.

11. IMPLEMENTATION WORKFLOW
Use the existing multi-agent development workflow. The main Claude conversation remains the orchestrator. Delegate independent UI, installer, Minecraft loading, and QA work to appropriate agents. All agents must follow the same Workbench design specifications. Avoid overlapping modifications and conflicting visual decisions.
Phase 1 — Inspect: Audit the current Workbench implementation and identify specific visual and functional issues.
Phase 2 — Propose: Present a concise refinement plan with the most important design decisions, including the Store placement, account pill, version cards, and main-menu icons. Show previews where useful. Wait for approval before major visual changes.
Phase 3 — Implement: After approval, make focused, coordinated changes. Preserve the existing architecture and working functionality wherever possible.
Phase 4 — Verify: Run builds, tests, visual checks, and regression checks. Correct inconsistencies before considering the work complete.
Phase 5 — Report: Provide a final checklist showing: What changed. What was preserved. Which screens were refined. Whether the real logo is used everywhere. Whether all version cards were updated. Whether Play works correctly. Whether the installer and uninstaller match. Which Minecraft loading screens were verified. Whether 1.8.9 was tested. Remaining issues or limitations.
Do not mark anything complete without supporting verification.

FINAL DESIGN DIRECTION
I like Workbench. Keep it. What I want now is refinement, not reinvention.
Make it feel like every element was designed by the same experienced design team.
Every button should have a purpose. Every dropdown should feel related to the surrounding controls. Every container should fit its content naturally. Every spacing decision should be intentional. Every icon should feel integrated. Every version card should feel carefully composed. Every screen should feel unmistakably Origin.
The installer, launcher, Minecraft loading screens, and main menus should feel like connected parts of one premium product.
Keep the Minecraft and gaming personality, but express it through sophisticated design rather than excessive decoration.
Preserve the approved in-game Origin Mod Menu.
The final result should feel more polished, more coherent, more intentional, and more professional than the current Workbench implementation, without losing what makes Workbench appealing.
Start by inspecting the current implementation and presenting your proposed refinements before making major changes.

(Orchestrator note: "the current Workbench implementation" = the concept A mock in design/concepts.html and view/A-Workbench-*.jpg; nothing is built yet. The mock used a recreated ring logo, which is exactly what Will forbids here.)
