# Waypoints — design & scoping (planning only)

Status: **planning** (2026-07-21). Not started in code. Target first version: **1.21.1**
(current focus per CLAUDE.md working order), then port per mandate 5.

## What it is
An in-world waypoint system: player-placed markers rendered in the world (beam +
floating label + distance), with a management screen and quick-add keybind. Origin
has no minimap, so waypoints are the "where is X" feature players expect from Lunar/
Feather — done the Origin way (monochrome, vanilla-legible, per-version best-fit).

## Core features (v1 scope)
- **Create** a waypoint at your current position, or by typed coordinates.
- Fields per waypoint: **name, x/y/z, dimension, color, icon(optional), visible,
  show-beam, show-distance**.
- **In-world render**: vertical beam (like a beacon, colored), a billboarded label
  above it (`name` + live `distance`), scaled by distance so far ones stay readable.
- **Off-screen edge indicator** (HUD): a small arrow/marker at the screen edge
  pointing toward an active off-screen waypoint (toggleable).
- **Management screen**: list, add, edit, delete, toggle each; reached from the
  Waypoints mod card in the Right-Shift menu.
- **Quick-add keybind**: drop a waypoint at your feet instantly (auto-named).
- **Scoping**: waypoints saved per **world/server + dimension**, so they don't
  bleed across saves (key off `ServerData.ip` / singleplayer world name + dim id).

## Nice-to-have (later phases)
- **Death waypoint**: auto-drop a "Death" waypoint where the player last died.
- **Temporary/shared waypoints** (chat-link parsing) — server-dependent, defer.
- **Ordering/grouping**, import/export.

## Data model
- Persist as JSON under Origin's config dir, mirroring `ModsConfig` conventions
  (single authority, eager save). One store keyed by `world|dimension` → list of
  waypoint records. Reuse the existing `OriginColorPicker` for the color field so
  the picker/UX matches every other Origin color (and chroma comes free).

## Rendering approach (per-version — this is the hard part)
- **1.21.x (GuiGraphics era)**: hook Fabric API's `WorldRenderEvents.AFTER_ENTITIES`
  (or `LAST`) for the beam + label in world space; the HUD edge-indicator draws in
  the existing HUD pass (`HudElements`-style). Billboard the label to face the
  camera; depth-test the beam so terrain occludes it (optional toggle for see-through).
- **Pre-1.20 (PoseStack backend)**: same idea but through each module's `Gfx`
  wrapper + the older world-render hook — its own render path (mandate 5).
- **Legacy Forge (1.8.9/1.12.2)**: `RenderWorldLastEvent`, no Fabric API — separate
  port, later.
- Keep the look monochrome/one-accent per Brand; color is the player's choice per
  waypoint, but chrome (label bg, arrow) stays Origin-styled.

## UI surfaces
- A **"Waypoints" mod card** in the mod menu (icon: a real item — e.g. a banner or
  lodestone) with options: enabled, show beams, show labels, show distance, edge
  indicator, max render distance, label scale, death waypoint.
- **"Manage Waypoints"** button on that page → the list/editor screen (Origin-styled,
  reuses `OriginUi.panel`, the color picker, `OriginTheme` tokens).

## Phasing
1. Data model + management screen + mod card (no world render yet) — testable.
2. In-world beam + billboard label + distance.
3. Off-screen edge indicator + quick-add keybind.
4. Death waypoint; then per-version ports (pre-1.20, legacy).

## Open questions for Will (when available — not blocking planning)
- Beam style: solid beacon-like beam, or a subtler vertical line / floating diamond?
- Should waypoints be per-dimension-visible only (Nether waypoint hidden in Overworld)?
- Edge indicator on by default, or opt-in?
- Icon support in v1, or name+color only to start?
