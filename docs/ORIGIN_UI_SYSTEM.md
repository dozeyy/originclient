# Origin Workbench UI system

## Refined execution brief

Redesign Origin Client as one coherent Minecraft-native product across the launcher, every supported Fabric module from Minecraft 1.21.1 through 1.21.11, and the staged 26.x module. Preserve each version's loader and rendering constraints while giving every Origin-owned screen, vanilla widget skin, settings control, HUD editor, overlay, and launcher surface the same visual language and interaction states.

The interface must feel crafted from Minecraft materials rather than copied from a generic web dashboard: deep deepslate surfaces, warm parchment text, emerald interaction accents, lapis information, redstone danger, and gold warnings. Use Minecraft's native font renderer for every in-game word, number, setting, HUD label, version identifier, and control. The Windows launcher uses Monocraft, an OFL-licensed Minecraft-style face, because WPF cannot consume Minecraft's bitmap glyph atlas directly. Maintain hierarchy through size, case, spacing, color, and placement rather than mixing typefaces.

Hover feedback begins immediately and completes quickly without snapping: 48 ms hover-in, 64 ms hover-out, eased color and edge changes, no positional drift. Press feedback is 42 ms and tactile. Geometry never changes under the pointer. Use the same rest, hover, focus, pressed, selected, disabled, loading, and destructive rules across all components.

Build every affected module and the launcher, run focused boot checks, capture real client renders at representative resolutions and GUI scales, and do not call the redesign complete until the code and rendered result are both verified.

## Identity: Origin Workbench

Origin Workbench combines Minecraft's workbench/deepslate material language with the precision expected from a modern performance client. It is intentionally quieter than a neon gaming dashboard and less rounded than a web app.

### Color roles

| Role | Hex | Use |
| --- | --- | --- |
| Voidstone | `#0E1110` | root background |
| Deepslate | `#171C19` | panels and HUD glass |
| Polished deepslate | `#202720` | raised surfaces |
| Parchment | `#F1E9D2` | primary text |
| Stone | `#B8B5A6` | secondary text |
| Emerald | `#5FD09B` | focus, selection, primary action |
| Lapis | `#6FAEE8` | information |
| Redstone | `#E06B5B` | destructive/error |
| Gold | `#D8B45B` | warning/favorite |

Emerald is the only general-purpose interaction accent. Lapis, redstone, and gold are semantic and must not decorate ordinary controls.

### Typography roles

- In-game: Minecraft's active vanilla `Font` for every text role, including menus, settings, HUD, search, profiles, tooltips, and title controls.
- Launcher: bundled Monocraft for every text role, including diagnostics and technical detail. Segoe/Consolas are fail-soft fallbacks only.
- Hierarchy comes from size, capitalization, position, spacing, and semantic color—not from a second typeface.
- Text measurement and truncation must use the same renderer that draws the text.

### Shape and depth

- Small controls: 1 px radius.
- Rows and cards: 2 px radius.
- Dialogs and large shells: 3 px radius.
- Toggles and sliders use square block handles; pills are not part of the core component language.
- Raised controls use a lighter top edge and a hard dark lower frame. Blurred card shadows and idle glows are avoided.

## Interaction contract

| State | Visual response | Timing |
| --- | --- | --- |
| Rest | deepslate fill, dark frame | immediate |
| Hover | one value step brighter, emerald-mineral edge | 48 ms in / 64 ms out |
| Focus | persistent 2 px emerald frame; never color-only | 80 ms |
| Pressed | darker fill and stronger inset edge | 42 ms |
| Selected | soft emerald wash plus solid indicator | 80 ms |
| Disabled | 45% opacity, no hover response | immediate |
| Loading | stable geometry, local progress indicator | 160 ms loop phases |
| Destructive | redstone label/edge, never a generic emerald primary | same timings |

Motion uses ease-out curves and opacity/color/scale only where scale cannot move neighboring layout. Hover never translates or resizes a hit target. Reduced-motion mode should resolve every state in one frame while retaining the state colors and focus frame.

## Component rules

- Buttons: sentence case by default; one primary action per decision area. Icon-only buttons always have an accessible name and tooltip.
- Settings rows: label and description on the left, current value/control on the right. Use the same 32 px minimum control height and alignment grid everywhere.
- Toggles: square inventory-style track and handle; emerald means on, neutral charcoal means off. Redstone is reserved for destructive/error.
- Sliders: block handle and squared track; show the value while focused, hovered, or dragged; support keyboard increments and reset.
- Search: `Ctrl+F` focuses it, `Esc` clears then closes, and empty/no-results states explain the next action.
- Tooltips: appear after 300 ms, remain inside the viewport, and never hide the focused control.
- Dialogs: default focus is safe; destructive confirmation names the affected item.
- HUD editor: keep native Minecraft text and use Workbench surfaces only for editor chrome.

## Version contract

- 1.21.1 remains the visual reference implementation and screenshot harness.
- 1.21.4 covers the 1.21.2–1.21.4 family.
- 1.21.6 covers the 1.21.6–1.21.7 family.
- 1.21.8, 1.21.10, and 1.21.11 retain their own renderer/input adaptations.
- 1.21.5 remains buildable but is not promoted back into the public launcher lineup.
- 26.2 remains staged until the release gate is explicitly approved, even when it compiles and boots.

## Recommended next additions

1. Add a command palette opened with `Ctrl+K` for mods, settings, profiles, and screens.
2. Add favorites and recently changed settings to reduce navigation time.
3. Add searchable setting synonyms and keyboard-first result navigation.
4. Add profile diff/import/export with an explicit compatibility preview.
5. Add reduced motion, high contrast, UI scale preview, and color-vision presets.
6. Add a per-version visual regression suite using the existing 1.21.1 screenshot harness as the seed.
7. Add a small in-product “What changed” panel for new Origin features without interrupting launch.
