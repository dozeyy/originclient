# Mod menu + HUD editor redesign: feature inventory (2026-10-12)

Will's brief: combine Lunar's mod menu and HUD editor with Origin's, keep what's
best of each, and make the whole thing flow together, judged by a separate
reviewer round after round until it's excellent. Scope: the mod menu (Right
Shift -> Mods) and the HUD editor. 1.21.1 first.

Lunar reference: Lunar's own site and repos are not reachable from the cloud
sandbox (network policy), so Lunar's side is described from its documented,
long-standing behaviour (patch notes, the Apollo mod schema) rather than fresh
screenshots. Origin's side is read from the 1.21.1 code and the real in-game
captures in the `claude/dev-shots` branch (.github/workflows/dev-shots.yml).

Score = how much the feature adds for a player (0-5). Verdict = what the
combined design does with it.

## Mod menu

| Feature | Lunar | Origin today | Score | Verdict |
|---|---|---|---|---|
| Opens from the HUD editor (Right Shift shows the HUD, a centred MODS button) | yes | yes (quick editor) | 5 | keep |
| Category filters | top chip row (All / New / HUD / Server / Mechanic) | left rail (All / HUD / Visual / Gameplay) | 4 | top chips: frees the full width for cards, one scan line |
| Search | top-right field, also matches option names | settings-page search only? (check shots) | 5 | one search in the header, matches mod names, descriptions and option names |
| Mod card | big icon, name, full-width ENABLED/DISABLED bar, gear | centred icon, name, pin, switch | 5 | icon + name + one clear state bar; whole-bar click toggles, gear/card opens settings |
| Favourites | star | pin | 3 | keep pin, pinned float to the top |
| Inspector pane (hover detail) | no | yes, wide windows | 2 | drop: cards say enough, settings are one click away |
| Settings page | options list + live preview of the HUD element | options list, search | 5 | options left, LIVE PREVIEW of the element right (HUD mods) |
| Per-option reset | yes | no | 3 | add a small reset arrow on changed options |
| Profiles / Settings / Cosmetics | tabs in header | rail rows | 3 | header tabs next to Mods |
| Layout density (Auto / 3 / 2 across) | resizable menu | setting (v1.0.46) | 3 | keep the setting |
| Back / Esc | back arrow on settings | back chevron | 5 | one rule: Esc and Back always go up exactly one level |

## HUD editor

| Feature | Lunar | Origin today | Score | Verdict |
|---|---|---|---|---|
| Drag elements | yes | yes | 5 | keep |
| Snap to screen centre lines | yes | yes | 4 | keep |
| Snap to screen edges + other elements' edges/centres, with guide lines | yes | no | 5 | add (the biggest feel upgrade) |
| Resize | corner drag | one corner handle, anchored scaling | 4 | keep Origin's anchored resize |
| Right-click an element -> its settings | yes | no | 5 | add, and settings has "Edit position" back |
| Turn element off from the editor | via menu | hover X | 3 | keep the X |
| Element name on hover | label | no | 3 | add a small name tag above the hovered box |
| Arrow-key nudge (1 px, Shift = 10 px) | no | no | 3 | add (precision without a grid) |
| Undo (Ctrl+Z) / reset position (R) | reset | R resets hovered | 3 | keep R, add Ctrl+Z |

## The flow (how it fits together)

Game -> Right Shift -> HUD editor (logo + MODS) -> MODS -> mod menu -> card ->
settings (with live preview, "Edit position") -> back to the editor with that
element selected. Right-click in the editor jumps straight to that element's
settings. Esc always goes up one level and never skips: settings -> menu ->
editor -> game.
