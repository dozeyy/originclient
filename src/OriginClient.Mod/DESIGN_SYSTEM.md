# Origin Client — In-Game UI Design System (implementation prompt)

Source of truth: `website/index.html`, `website/css/styles.css`, `website/js/main.js`.
Every value below was read directly out of those files, not guessed. This doc exists
so a future implementation pass has the exact tokens plus the specific, already-paid-for
lessons from three prior attempts — instead of re-deriving both from scratch.

> **Settled 2026-07-08: custom glyph rendering is off the table for now.**
> A third attempt (bitmap atlas + forced linear texture filtering, no font-provider
> involvement at all) still read as "bold and blocky," then actively garbled/
> overlapping after a fix attempt, live in Will's own client. That's three different
> techniques across three sessions all failing live despite passing every check
> available without a running client. **Decision: use Minecraft's own vanilla font
> for all in-game text.** All the tokens below (colors, spacing, panel styling, the
> cursor-glow background, button motion) still apply — none of them depend on custom
> glyph rendering. Only the Typography section and §6a/§6b (custom text rendering)
> are superseded by this decision; don't re-attempt them without first solving the
> live-verification gap described in §6f (a way to actually see the running client
> during development, not just after a full round-trip through Will).
>
> **Amended 2026-07-08 (and reverted same day by Will's call): Inter via
> Minecraft's TTF font provider WORKS.** Overriding
> `assets/minecraft/font/default.json` from mod resources with an Inter Medium
> `ttf` provider (size 10, oversample 4, vanilla providers as fallbacks)
> rendered real Inter across the whole game, confirmed live — it is NOT the
> banned custom glyph rendering (Minecraft's own engine draws it). Will chose
> to go back to the default Minecraft font for ALL text anyway; the override
> was removed. If Inter-in-game ever comes back, this is the mechanism — do
> not re-attempt hand-rolled glyph atlas/draw code, that ban stands.

## 0. Read this first — prior attempts and why they were rolled back

This exact goal ("make the in-game client look like the website: real font, animated
interactive background, everywhere") has been attempted **twice** in this repo and
reverted both times. Full detail in `MEMORY.md` (2026-07-06 "Lunar-Client-vibe UI pass"
through 2026-07-07 "Full UI rewrite attempted and abandoned"). The short version, so it
isn't repeated blind:

- **Font, attempt 1 — real TTF via Minecraft's built-in `ttf` font provider.** Used
  Bahnschrift. Minecraft 1.21.1's TTF path is **LWJGL FreeType** (`FT_New_Memory_Face`),
  not STB. Bahnschrift ships as a *variable* font (`fvar` table) and FreeType doesn't
  reliably pick a named instance out of one — text rendered garbled. Re-tried with a
  static-instanced build (`fonttools varLib.instancer wght=400`) — still looked wrong
  live ("completely blurred").
- **Font, attempt 2 — pre-rendered bitmap glyph atlas.** Rasterized the font to a PNG
  grid and registered it as a `bitmap` font provider. Two real bugs found and fixed:
  (1) the PNG must live under `assets/<ns>/textures/font/...` — Minecraft's bitmap
  provider auto-prepends `textures/` to the configured path; putting it directly under
  `assets/<ns>/font/` fails silently and falls back to vanilla. (2) GDI+ anti-aliased
  `DrawString` onto a transparent bitmap leaves faint non-zero-alpha residue outside the
  glyph, which fools Minecraft's own glyph-width scanner into measuring almost every
  character as ~30px wide (monospaced-looking spacing). Fixed by rendering **opaque
  black-on-white, no alpha channel**, then converting luminosity → alpha as a
  post-process. This version was verified correct by replicating Minecraft's own
  width-scanning algorithm — but was never confirmed against a real, running client
  before the whole pass was torn out.
- **Structural ceiling that applies to *both* approaches**: Minecraft's 2D GUI
  (`GuiGraphics`) draws in a low-resolution *virtual* coordinate space tied to GUI
  Scale — each unit it draws can cover a multi-screen-pixel block. A `ttf` provider and
  a bitmap atlas both still get composited through that same blocky system. This is
  exactly why "smooth, not pixelated, at any scale" requires the shader-overlay
  approach in §6b, not another font-provider swap.
- **The real blocker throughout both passes**: there was no way to screenshot the live
  dev Minecraft window (computer-use never resolved the window under any title tried),
  so every round of feedback depended on Will manually looking at the game and
  reporting back. Two rounds of "passes static/algorithmic verification, looks wrong
  live" happened *because of* that gap, not because the analysis was sloppy. Anyone
  picking this up again should solve that gap first, or explicitly plan for
  manual-screenshot-driven iteration — don't assume a third blind attempt will land.
- Final state before this doc: **100% stock vanilla Minecraft menus**, all custom
  gui/mixin files from that pass deleted. Only the pre-existing feature mods (Zoom,
  Freelook, HUD text, Toggle Sprint/Sneak, Fullbright) remain, and today's `OriginHud`
  is plain vanilla-font text with no panel/box at all (`OriginHud.java`).

Everything from here down is the exact spec to build against, plus (§6) how to build
it without repeating the failures above.

---

## 1. Design tokens

Pulled from `website/css/styles.css` `:root`. Hex/rgba as authored, plus the Java
`0xAARRGGBB` int Minecraft rendering code actually wants.

### Colors

| Token | CSS value | Java ARGB int | Use |
|---|---|---|---|
| `bg` | `#050505` | `0xFF050505` | screen background base |
| `bg-alt` | `#0a0a0a` | `0xFF0A0A0A` | alternating section background |
| `panel` | `#101010` | `0xFF101010` | solid panel fill |
| `panel` @ 55% (HUD panel bg) | `rgba(16,16,16,0.55)` | `0x8C101010` | HUD/mod panel background |
| `panel-alt` | `#161616` | `0xFF161616` | secondary panel fill |
| `stroke` | `rgba(255,255,255,0.08)` | `0x14FFFFFF` | hairline borders |
| `stroke-strong` | `rgba(255,255,255,0.18)` | `0x2EFFFFFF` | emphasized borders |
| `text` | `#F5F5F5` | `0xFFF5F5F5` | primary text |
| `text-dim` | `#9A9A9A` | `0xFF9A9A9A` | secondary text (HUD row values) |
| `muted` | `#616161` | `0xFF616161` | tertiary text (HUD row labels, eyebrows) |
| `accent` | `#FFFFFF` | `0xFFFFFFFF` | the one accent — stays white/mono, no hue, confirmed with Will |
| `accent-glow` | `rgba(255,255,255,0.35)` | `0x59FFFFFF` | glow/shadow color behind accent text, cursor halo |
| `accent-dim` | `rgba(255,255,255,0.55)` | `0x8CFFFFFF` | cursor core glow |

Do not introduce a new hue anywhere in this system — every "color" in the website is
white/gray at varying opacity. This matches the launcher's own Deskify palette
decision (see root `CLAUDE.md` → Brand).

### Typography — SUPERSEDED, see banner above

~~Website font: Inter (400/500/600/700/800)~~ — three attempts at rendering a custom
font in-game (Bahnschrift via `ttf` provider, a bitmap atlas via a `bitmap` provider,
a hand-rolled bitmap-atlas-plus-forced-linear-filtering renderer) all failed live.
**Use Minecraft's own vanilla font for all in-game text instead.** It already reads
clean at small HUD sizes (confirmed directly in Will's own screenshots — the vanilla
FPS/XYZ/Ping text next to every one of the failed custom-font attempts looked fine).

The table below is kept for reference (weight ramp / size scale intent, letter-
spacing, color-per-role) in case a future session solves the live-verification gap
and revisits this — but do not build against it without re-reading the banner at the
top of this file first.

| Element | Size | Weight | Letter-spacing | Color |
|---|---|---|---|---|
| Eyebrow / section label | 12px | 500 | 0.18em, uppercase | `muted` |
| Hero / large heading | 42–84px (fluid) | 700 | -0.02em | `text` |
| Body / sub text | 16px | 400 | normal | `text-dim` |
| Big stat number (e.g. "1,200+") | 72–140px (fluid) | 800 | -0.03em | `accent` + glow |
| HUD big value (e.g. FPS number) | 28px | 700 | -0.01em | `text` |
| HUD unit label | 12px | 400 | 0.08em | `muted` |
| HUD row text | 11px | 400 | 0.06em | `text-dim` (value) / `muted` (label) |
| Button label | 14px | 600 | 0.01em | contextual |
| Small link / nav | 13–14px | 500 | 0.02em | `text-dim`, hover → `text` |

For now: use vanilla Minecraft `Font`/`GuiGraphics.drawString`, tinted per the color
column above, sized as close to the intent above as vanilla's fixed-size font
reasonably allows. The panel/spacing/color/motion system around that text (§2-§5) is
what should carry the "premium" look, not the glyphs themselves.

### Spacing / radius / motion

- 8px grid: `8 / 16 / 24 / 32 / 48 / 64 / 96`
- Radius: small `6px`, medium `10px` (HUD panels use this), large `14px`
- Easing: `ease-out = cubic-bezier(0.16, 1, 0.3, 1)`, `spring = cubic-bezier(0.34, 1.56, 0.64, 1)`
- Duration: fast `0.15s` (press/hover color), medium `0.3s` (glow/shadow/size changes)

---

## 2. HUD stat panel — canonical template for every mod's UI

This is the exact box the task refers to as "the little box that says coords ping
cpu" (`.hud__panel--stats` in the CSS, rendered in the hero mockup). **Every mod's
on-screen UI (coords, ping, cpu, and any future one) must render through one shared
component built to this spec** — not restyled per-mod. Coords/Ping/CPU stay separate,
independently toggleable mods, but they all draw through the same panel component so
they're pixel-identical in font, spacing, and color.

```
background:        rgba(16,16,16,0.55)     -> 0x8C101010
border:             1px solid rgba(255,255,255,0.08) -> 0x14FFFFFF
border-radius:      10px
backdrop blur:      10px   (see §6b — real blur requires the shader overlay;
                             a flat semi-transparent fill is the honest fallback
                             if blur isn't implemented, not a fake/approximated blur)
padding:            14px 18px
row gap:            8px
font-size:          11px
letter-spacing:     0.06em
row layout:         label ..... value  (space-between)
label color:        muted   (0xFF616161)
value color:        text-dim (0xFF9A9A9A)
```

Rows, in the reference mockup: `COORDS  142, 74, -308` / `PING  12ms` / `CPU  0.8%`.
Same panel shape also covers a single big-number variant (`.hud__panel--fps`):
28px/700 value + 12px/muted unit label, baseline-aligned, no visible box — just the
padding — when a mod wants one hero number instead of a label/value list.

`OriginHudPanel` (new shared component) should take a list of `(label, value)` rows
and render this exact box; `OriginHud`'s current plain-text implementation
(`OriginHud.java`) should be rebuilt on top of it rather than extended in place.

---

## 3. Buttons / interactive elements

From `.btn`, `.btn--primary`, `.link-underline` in `styles.css`:

- Base: 12px/24px padding (14px/30px for the large hero CTA), 10px radius, 600
  weight/14px label, `border: 1px solid transparent`.
- **Hover** (primary): lift `translateY(-2px)` + `box-shadow: 0 8px 32px accent-glow`
  (a soft white bloom, not a hard shadow), transition `0.3s ease` for the shadow,
  `0.15s ease-out` for transform.
- **Active/press**: `scale(0.96)`, instantaneous-feeling (fast transition).
- Secondary/link style (`link-underline`): text-dim, underline expands from 0 to
  full width on hover (`transform: scaleX(0→1)`, `0.3s` ease-out, left-anchored),
  color brightens to `text` on hover.

In-game translation: the prior pass's `OriginMenuButton` already used real
elapsed-wall-clock easing (`System.nanoTime()` deltas, not MC's fixed 20-tick clock)
for hover scale + color lerp — that part is correct and should be carried forward.
What it didn't yet do: the `translateY(-2px)` lift (offset the button's draw Y by a
couple of px on hover, eased) and the glow bloom on hover (a soft blurred white quad
behind the button, see §6b for how to do a real blur/glow cheaply via shader instead
of per-pixel `fill()` calls — see the perf bug in §6f).

---

## 4. Interactive mouse-reactive background

From `.cursor-glow` + the `pointermove`/`tick()` logic in `main.js`. Two independent
radial-gradient layers, both `mix-blend-mode: screen`:

- **Core** — 130px diameter (200px on hover of an interactive element), opacity
  0.55 (0.85 on hover), **snaps directly to the cursor position every frame**, no
  lag. Radial gradient: `rgba(255,255,255,0.55)` center → transparent at 70% radius.
- **Halo** — 560px diameter (720px on hover), opacity 0.32 (0.5 on hover), **lags
  behind the cursor** via per-frame linear interpolation: `pos += (target - pos) * 0.12`
  each animation frame (this exact factor, not an approximation). Radial gradient:
  `accent-glow` (`rgba(255,255,255,0.35)`) center → transparent at 70% radius.
- Both layers pause/skip entirely under `prefers-reduced-motion` on the website;
  there's no in-game equivalent setting today, so this can always run, but keep the
  hook in mind if an accessibility toggle gets added later.

**Where it applies**: every menu-style screen the mod owns or reskins — main/title
screen, Singleplayer, Multiplayer, Settings/Options, any Origin mod-menu overlay.
**Where it does NOT apply**: the actual gameplay HUD overlay (`OriginHud` /
coords-ping-cpu panels) — there is no mouse cursor visible while playing, so that
surface only gets the panel styling from §2, never the cursor glow. This matches the
task description's own wording ("every HUD besides the in-game mod overlay HUD").

The prior pass already validated *where* to hook this in Minecraft: a mixin on the
shared `Screen.renderPanorama` method (not `TitleScreen`'s own override) reaches
title, world-select, multiplayer, and options-from-title in one place, confirmed via
bytecode that `PauseScreen` doesn't override it separately. **Must skip when
`Minecraft.level != null`** (actually paused mid-game) — vanilla shows a blurred
capture of the running world behind the pause menu there, and replacing that with the
rings/glow background would hide the game the player is paused in. That call was
already made correctly last time; keep it.

---

## 5. White text glow

Two distinct glow strengths in the CSS, both `text-shadow`, both using `accent-glow`:

- Large accent numbers (the "1,200+" stat, bar-chart Origin value): `0 0 60px
  rgba(255,255,255,0.35)` — big, soft bloom.
- Medium accents (marquee "Origin" repeats, bar values): `0 0 24–30px
  rgba(255,255,255,0.35)` — tighter bloom.

In-game: reserve the strong glow for one or two hero numbers per screen (e.g. an FPS
counter if ever surfaced large), and the tighter glow for the mod's own accent
wordmarks/headers. Ordinary HUD row text (§2) has **no glow** on the website — don't
add one there, it would fight the "little box" reference the task points at
specifically.

---

## 6. Minecraft/Fabric implementation guidance

### 6a. Why "smooth, not pixelated, at any scale" needs more than a font swap — and why this is shelved, not being attempted right now

See §0 — both a `ttf` font provider and a bitmap glyph atlas ultimately get
composited through `GuiGraphics`'s blocky, GUI-Scale-tied virtual coordinate space.
**A third attempt confirmed this isn't just theoretical**: a hand-rolled renderer
(bitmap atlas + `AbstractTexture.setFilter(true, false)` forcing GL_LINEAR, drawn via
`GuiGraphics.blit`, deliberately bypassing Minecraft's own font provider entirely)
still read as "bold and blocky" live, and a follow-up fix (baking the atlas closer to
real display size to reduce the minification ratio) made it *worse* — visibly
garbled/overlapping text — in a way that wasn't reproducible or debuggable from a
sandbox with no live client access. Per Will's own call: **don't keep iterating on
this blind.** Use vanilla Minecraft text (see the Typography section above) until
someone can actually watch the client render while changing this code, not just
read a screenshot after the fact each round trip.

### 6b. If this is ever revisited: what "build a custom graphics generator" means concretely

Keeping this for whoever solves the live-verification gap someday — not a current
task.

- **Text**: bake Inter into a signed-distance-field (SDF) glyph atlas (a build-time
  step, e.g. `msdfgen` or `stb_truetype`'s SDF mode, checked into the repo as a
  generated resource — not regenerated at runtime). Render glyphs as textured quads
  sampling that atlas with a `smoothstep` alpha threshold in a fragment shader —
  this is what gives crisp edges at *any* draw scale (unlike a fixed-resolution
  bitmap atlas) and makes the glow in §5 nearly free (widen the smoothstep band on
  the same sample instead of a separate blur pass).
- **Shader plumbing**: Minecraft 1.21.1 supports custom core shaders via
  `assets/<ns>/shaders/core/*.json` + GLSL, loaded the same way vanilla's own
  `CoreShaders` are. Draw the SDF-text quads and the cursor-glow gradient (§4) as
  real GL draws in **actual screen-pixel space**, hooked in after a `Screen`'s normal
  render pass (a tail mixin on `Screen.render` or a `HudRenderCallback`, depending on
  whether it's a menu screen or the gameplay overlay) — this sidesteps
  `GuiGraphics.fill()`'s virtual-coordinate blockiness entirely, which is the actual
  fix for "no pixelated letters," not a font-file change.
- Be upfront this is real, substantial engineering (a small custom text/graphics
  engine, not a resource swap) — scope it as its own milestone, not a drive-by change
  alongside unrelated feature work, the same way the prior pass's own postmortem
  flagged it.
- The baked Inter TTFs and a working atlas-generation pipeline already exist
  (`tools/font-atlas/`, unused but not deleted) — the gap isn't the asset pipeline,
  it's exclusively the live in-game rendering/verification loop.

### 6c. Interactive background implementation notes

- Mouse position is already available every frame via
  `Minecraft.getInstance().mouseHandler` in screen space — no new input plumbing
  needed.
- Track the halo's lagged position in a small per-screen (or singleton, reset on
  screen open) state object, updated once per frame with the exact `0.12` lerp
  factor from §4 — mirror `main.js`'s `tick()` function directly rather than
  re-deriving the constant.
- Render both layers as a single fullscreen-quad fragment shader computing the two
  radial gradients (GPU-cheap, one draw call) — do **not** implement this as
  per-pixel CPU `fill()` calls; see §6f for exactly why that failed before.

### 6d. Buttons

Carry forward `OriginMenuButton`'s real-time-based hover/press easing from the prior
pass; add the `translateY(-2px)` hover lift and hover glow bloom, and the
`scale(0.96)` press squash from §3. The glow bloom doesn't need §6b's shelved
shader work — a precomputed radial-gradient PNG (same technique as §4's cursor
glow) drawn as a scaled/tinted textured quad behind the button, via a normal
alpha-blended `blit`, is enough and carries none of the custom-text-rendering risk.

### 6e. Mod-UI panel component

Build `OriginHudPanel` once per §2's spec (background, border, radius, padding, row
layout, font/color), and have Coords, Ping, and CPU each supply rows to it rather
than drawing their own boxes. This is what makes "every mod's UI... exact font style
spacing colors as that one [Coords]" true by construction instead of by convention.

### 6f. Process guardrails for whoever implements this next

- **Solve the live-verification gap before attempting custom text rendering again —
  don't just "accept" it and iterate blind.** Three separate rounds of "correct on
  paper, wrong (or worse) live" have now happened across three sessions, specifically
  on custom font rendering — including a third attempt (2026-07-08) where screenshots
  *were* available each round and it still took two live iterations to go from "too
  blocky" to "actively garbled," at real cost to Will's time (full rebuild + relaunch
  + navigate a world + screenshot, each round). Screenshots-after-the-fact are much
  better than nothing but are not the same as being able to watch the change take
  effect — that gap is why this got shelved rather than pushed a fourth time. Some
  way to actually observe the client live during development (not just after a full
  round-trip) is a precondition for re-attempting this, not a nice-to-have.
- **Never do per-pixel `fill()` loops** for glow/blur/rounded corners — the last
  attempt's "laggy as all hell" bug was exactly this (a corner-fill routine issuing
  one draw call per pixel inside the radius, blowing up to tens of thousands of calls
  a frame at glow-sized radii). Use a shader (§6b/6c) or, at minimum, row-based fills.
- **Any bitmap font atlas** (if one is ever used again for anything) must be
  rendered opaque, no alpha, with luminosity converted to alpha as a post-process —
  and placed under `assets/<ns>/textures/font/...`, since Minecraft's bitmap
  provider silently prepends `textures/` to the configured path.
- **Check for a variable-font (`fvar`) table** before embedding any TTF directly via
  Minecraft's own font provider; it's FreeType-backed in 1.21.1 and doesn't reliably
  resolve a named instance. (Moot if going the SDF-atlas route in §6b, which sidesteps
  Minecraft's font provider entirely — one more reason to prefer that path.)
- **Confirm real APIs via `javap`/bytecode** before mixin-ing into any vanilla class,
  same discipline used throughout this project already — don't infer behavior from a
  mapping file's absence of an override listing.
- Icons: use real raster assets (Will's existing icon pack, referenced in
  `MEMORY.md`), not procedurally point-sampled dots — that read as messy scatter at
  real button sizes last time.

---

## 7. Scope

This spec covers the mod's own custom-drawn surfaces — Origin HUD panels and any
Origin-owned menu screens. It doesn't change the Phase boundaries in root
`CLAUDE.md` (mod loaders, mods browser, crash system are still Phase 2/3). It also
doesn't touch the WPF launcher, which already has its own, separately-implemented
Deskify theme (Bahnschrift, native WPF rendering — no pixelation ceiling to solve
there since WPF isn't drawing through Minecraft's GUI coordinate system).
