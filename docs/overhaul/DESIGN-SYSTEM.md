# Origin design system: Workbench

The one standard for every Origin surface: the launcher, the installer and uninstaller, the
website, the in-game main menu and the loading screens. Phase 2 of the overhaul (2026-10-25).
The rendered proof is `design-preview/index.html` next to this file. Open it from the repo
(it needs only its own `assets/` folder).

- **Direction:** Workbench (concept A), approved by Will. This document refines and codifies
  it. It is not a new direction.
- **Fixed point:** the in-game Origin Mod Menu (`gui/OriginModMenuScreen.java`, `gui/OriginDeck.java`).
  Its tokens are listed in section 15.1 as **protected**. Other surfaces may borrow from it. It
  never changes.
- **Supersedes** for the launcher, installer, website and title/loading screens:
  `docs/ORIGIN_UI_SYSTEM.md` (Monocraft launcher text, square toggles, 4 px radii, "no pills")
  and the "Brand" line in the project `CLAUDE.md` ("monochrome, no hue even in the glow").
  Where those files disagree with this one, this one wins. Phase 3 updates them.
- **Applies in:** Phase 3 (launcher, installer, website) and Phase 4 (in-game). This phase
  changes no app code.

Notation: `token.name` = value. Launcher, installer and web values are device-independent
pixels (WPF DIPs = CSS px). In-game values say **GUI px** (Minecraft's scaled units) or
**design px** (the Mod Menu's 1920x1080 units). Section 17 maps every token to its XAML key,
CSS variable and Java constant.

---

## 0. Principles

1. **One material, many sizes.** Deepslate surfaces, parchment text, one emerald signal. A
   compact dropdown and the Play button share radius logic, type, states and motion. Only
   their size differs.
2. **Containers fit their content.** Widths come from content plus padding. Fixed widths
   are only for grids and windows. A control is never wider than its content needs.
3. **Colour is small and soft.** It appears in icon glyphs, tiles in list rows, dots, badges,
   progress and selected rings. It never fills a large area. Emerald fills exactly one
   thing per screen: the primary action.
4. **Real brand, never redrawn.** One logo file and one wordmark file, referenced from one
   place per codebase (section 1).
5. **Every state is designed.** Rest, hover, press, focus, selected, disabled, loading,
   error. Nothing clickable looks inert, and nothing inert looks clickable.
6. **Performance first.** No blur, no glow, no animated shadows. Scrims on art are the only
   gradients.

---

## 1. Brand assets

### 1.1 The two master files

| Asset | Master file (never edited) | Size | SHA-256 of master is checked in Phase 3 |
|---|---|---|---|
| Logo (three tilted rings) | `src/mods/versions/1.21.1/src/client/resources/assets/originclient/textures/ui/origin_logo.png` | 256 x 256, white on transparent, ink box x 13-243, y 43-213 (230 x 170) | md5 `470b7e45e5ec999b1da3244e6923acf3` (all 23 in-repo copies match) |
| Wordmark ("ORIGIN") | `src/OriginLauncher.App/Assets/Brand/wordmark.png` | 430 x 88, white letters with a soft white halo baked in, ink box x 12-419, y 12-76 | md5 `562729b44dde2b5d583c85d1618f18c8` |

`Assets/origin.ico` holds the logo at 16/32/48/256 and stays the Windows icon.

### 1.2 Exports: plain resampling only

Every other size is made from the master by **area or Lanczos downscale. There is no
sharpening, recolouring, re-tracing, cropping of the ink, or re-padding.** One script makes them
(`tools/brand/export.py`, Phase 3), and CI compares each export's dimensions and source hash.

| Export | From | Sizes (px, square for the logo / height for the wordmark) | Used by |
|---|---|---|---|
| `origin_logo.png` (copy, byte-identical) | logo | 256 | launcher (`Assets/Brand/`), installer, every mod module, website |
| `origin_logo-{N}.png` | logo | 16, 20, 24, 32, 48, 64, 96, 128 | fixed-size raster hosts with no good scaler: 1.8.9 (no mipmaps in its GUI path), website `<img srcset>`, installer at 100/150/200 % DPI |
| `wordmark.png` (copy, byte-identical) | wordmark | 88 | launcher, installer, website, every mod module |
| `wordmark-{H}.png` | wordmark | 16, 18, 24, 32, 44 (heights, width proportional) | same as above |

The wordmark is **never upscaled** past 88 px tall. The in-game `textures/ui/wordmark.png`
(1540 x 524 in 20 modules, plus a third variant in 3 modules) is **not** the master. Phase 4
replaces those copies with exports of the launcher wordmark. See open question 1.

### 1.3 Sizes per surface

Logo size is the **canvas box** (the 256 square scaled). The visible rings fill about 90 % of the
width and 66 % of the height of that box.

| Surface | Logo box | Wordmark height | Lockup |
|---|---|---|---|
| Launcher top bar | 24 | 16 (78 wide) | logo, 6 gap, wordmark, both vertically centred on the bar |
| Launcher startup intro / launch cover | 64 | none | logo alone, centred |
| Installer and uninstaller title bar | 20 | none | logo, 8 gap, "Origin Setup" in `type.bodyStrong` |
| Installer hero (ready, done) | 48 | 24 | stacked: logo, 12 gap, wordmark, left-aligned |
| In-game title (1.21.1, GUI px) | 34 (TitleLayout.MARK) | 11 GUI px | stacked and centred, 6 GUI px gap |
| In-game loading screen | 72 design px (scaled by window height / 1080) | none | logo alone, centred |
| Website header | 28 | 18 | logo, 8 gap, wordmark |
| Favicon / taskbar | origin.ico / 16, 32 exports | none | none |

### 1.4 Clear space and placement

- **Clear space:** at least 25 % of the logo box on every side (6 px around the 24 px top-bar
  logo). Nothing else may enter it: text, edges or other icons.
- Always upright. The aspect ratio stays locked, and there is no rotation, skew, outline,
  shadow, glow, gradient or animation of the drawing itself. Fading, translating and uniform
  scaling of the whole image are allowed in transitions.
- Place it on `color.bg`, `color.surface` or `color.nav`, or on art that sits under a scrim of
  at least 60 % `color.bg`. If art shows through the ink, use more scrim. Do not add a box
  behind the logo.
- **Light theme:** the logo and wordmark are white art. On light surfaces they render through
  their own alpha as a mask, filled with `color.text` (WPF `OpacityMask` with an `ImageBrush`, CSS
  `mask-image`). The shape stays the master's own pixels. See open question 2.

### 1.5 One reference per codebase

So the wrong logo cannot come back, each codebase reads the brand from exactly one place:

| Codebase | The one reference | Replaces (Phase 3/4 removes) |
|---|---|---|
| Launcher | `Theme/Brand.xaml`: `Brand.Logo` (BitmapImage, pack URI `/Assets/Brand/origin_logo.png`), `Brand.Wordmark`; control `BrandLogo` (an `Image`, `RenderOptions.BitmapScalingMode=HighQuality`, size from `Size.Logo.*`) | `UI/Controls/OriginMark.xaml` (drawn rings), `StartupIntro.xaml` rings, `Effect.OriginGlow` / `OriginGlowSoft` |
| Installer | `Theme/Setup.xaml`: same two keys, `/Assets/Brand/` copies | `UI/OrbitMark.xaml(.cs)` |
| Website | `website/assets/brand/origin_logo.png`, `wordmark.png` (+ `-N` exports), used only through `<img class="brand-logo">`. No inline SVG symbol | inline SVG symbols in the pages, `js/world.js:396-425` ring drawing |
| In-game | `OriginUi.logo(...)` / `OriginScreenRenderer.drawLogo(...)` binding `originclient:textures/ui/origin_logo.png` | `OriginUi.mark()`, `ModIcons.cs:81-91` "Mark B", `ring-0..3.png` + `rings.json` (the morphing-rings loading animation) |

A CI grep (Phase 3) fails the build if a new drawn-ring geometry or SVG ring symbol appears
outside the deleted list.

---

## 2. Colour

### 2.1 Dark (default for every surface)

| Token | Value | Role |
|---|---|---|
| `color.bg` | `#0E1110` | window and page background, loading screen, installer body |
| `color.bgSunken` | `#0B0D0C` | progress tracks, behind art while it loads |
| `color.surface` | `#171C19` | panels, drawers, sheets, dialogs, installer content |
| `color.surfaceRaised` | `#1D2320` | cards, secondary buttons, dropdown triggers, menus, tooltips |
| `color.surfaceHover` | `#232A26` | hover on anything raised |
| `color.surfacePress` | `#191E1B` | press on anything raised |
| `color.nav` | `rgba(20,24,22,.88)` = `#E0141816` | the fixed top bar |
| `color.field` | `rgba(0,0,0,.28)` | text inputs and search |
| `color.line` | `rgba(255,255,255,.06)` | hairlines at rest: card edges, dividers |
| `color.lineStrong` | `rgba(255,255,255,.10)` | control edges, hover edges, menu edges |
| `color.text` | `#F1E9D2` (parchment) | primary text |
| `color.numeral` | `#F5F5F5` (neutral white, no hue) | every version number set on art: Home and cards (11.6) |
| `color.onArt` | `rgba(245,245,245,.82)` | eyebrow and range beside a numeral on art |
| `color.cornerShade` | `color.bg` from 88 % to 0 | the radial shade a numeral on art stands in (11.6) |
| `color.textDim` | `rgba(241,233,210,.68)` | secondary text, inactive nav, icons at rest |
| `color.textMuted` | `rgba(241,233,210,.52)` | meta, placeholders, captions (4.5:1 on `bg` and `surface`) |
| `color.accent` | `#5FD09B` (emerald) | primary fill, switch on, progress fill, selected ring, focus |
| `color.accentHover` | `#83E2B5` | primary hover |
| `color.accentPress` | `#4CB985` | primary press |
| `color.accentSoft` | `rgba(95,208,155,.14)` | selected row or tab wash |
| `color.accentLine` | `rgba(95,208,155,.60)` | focus ring, selected ring |
| `color.onAccent` | `#0A1710` | text and glyphs on emerald (9.6:1) |
| `color.success` | `#68C490` | installed, ready, done |
| `color.warning` | `#E9C46A` | needs attention, update available |
| `color.danger` | `#D9675A` | errors, destructive edges |
| `color.dangerText` | `#F98E80` | danger label text on dark (7:1+) |
| `color.dangerSoft` | `rgba(217,103,90,.14)` | danger button fill |
| `color.info` | `#6FAEE8` | informational notes |
| `color.scrim` | `rgba(10,13,12,.64)` | behind dialogs and the version sheet |

Measured contrast (WCAG): text on bg 15.7, textDim on raised 6.8, textMuted on bg 4.7,
accent on bg 9.9, dangerText on raised 7.0.

### 2.2 Light (launcher and installer only, a roadmap theme, built after dark)

| Token | Value | | Token | Value |
|---|---|---|---|---|
| `color.bg` | `#E8EBE6` | | `color.text` | `#16201A` |
| `color.surface` | `#F5F6F2` | | `color.textDim` | `#46524A` |
| `color.surfaceRaised` | `#FFFFFF` | | `color.textMuted` | `#5E685F` |
| `color.surfaceHover` | `#EEF1EC` | | `color.accent` / hover / press | `#1F7049` / `#1A6140` / `#175838` |
| `color.surfacePress` | `#E3E7E1` | | `color.onAccent` | `#FFFFFF` |
| `color.nav` | `rgba(245,246,242,.90)` | | `color.success` / `warning` / `danger` / `info` | `#2F8F5B` / `#8A5F0F` / `#A83F33` / `#2B67A3` |
| `color.line` / `lineStrong` | `rgba(22,32,26,.08)` / `.14` | | `color.field` | `rgba(22,32,26,.05)` |

The website and every in-game surface are dark only.

### 2.3 Soft accents (hues)

| Token | Dark | Light | Assigned to (fixed, the same on every surface) |
|---|---|---|---|
| `hue.emerald` | `#5FD09B` | `#1F7049` | Performance, Accessibility, "ready" |
| `hue.sky` | `#6FAEE8` | `#2B67A3` | Friends, Game, information |
| `hue.gold` | `#E9C46A` | `#8A5F0F` | Store, Cosmetics, Language, updates |
| `hue.lilac` | `#B49CE8` | `#6A4DB5` | Origin Client, Profiles, Java |
| `hue.coral` | `#E08A6B` | `#A94F2C` | Startup, Quit |

Where hue may appear:
- **Glyphs** of icons that lead to a place (Store, Friends, dock destinations), at 100 %.
- **Tiles** only in list rows where the icon labels the row (the Settings rail, Import scope
  rows): a 28 x 28 tile, `radius.sm`, filled with the hue at 16 %, glyph at 100 %
  (glyph-on-tile contrast 5.1-7.2).
- **Dots, badges, progress fills.**
- **The update chip** (11.4): its fill is the hue at 12 % (20 % on hover), glyph and label at 100 %.

Never on large fills, on a numeral on art, on body text, or as a box around a navigation or
dock icon (Will: "remove the unnecessary boxes").

---

## 3. Typography

**Family: Inter**, the only UI face on every surface.
- Launcher and installer: bundled static TTFs 400-800 (`tools/font-atlas/fonts/Inter-*.ttf`, OFL).
- Website: woff2 400/600/700 today. Phase 3 adds 800 (for numerals) and 500.
- In-game: the existing Inter MSDF atlas (400/600). No new in-game font. 1.8.9 uses `OriginTtfFont`.
- Technical text (crash details, paths): `Consolas` (Windows) / `ui-monospace`. Monocraft
  leaves the UI.

| Token | Size / line | Weight | Tracking | Case | Use |
|---|---|---|---|---|---|
| `type.label` | 11 / 14 | 600 | +0.10 em | UPPER | nav tabs, eyebrows, badges, section labels |
| `type.labelLg` | 16 / 20 | 600 | +0.08 em | UPPER | the eyebrow above the Home numeral only |
| `type.caption` | 12 / 16 | 500 | 0 | Sentence | status lines, meta, helper text, tooltips |
| `type.body` | 13.5 / 20 | 400 | 0 | Sentence | default text, descriptions |
| `type.bodyStrong` | 13.5 / 20 | 600 | 0 | Sentence | button labels, names, row titles |
| `type.title` | 16 / 22 | 600 | -0.005 em | Sentence | card titles, dialog titles, installer state titles |
| `type.action` | 16 / 20 | 700 | -0.01 em | Sentence | the Play label only |
| `type.heading` | 22 / 28 | 700 | -0.015 em | Sentence | page and sheet titles |
| `type.display` | 32 / 36 | 800 | -0.03 em | Sentence | installer hero line, empty-state headline |
| `type.numeral` | figures = 25 % of the art's height | 800 | -0.04 em | Figures | every numeral on art: Home and version cards (11.6) |

- **Numerals on art:** Inter 800 at its **natural width**, proportional lining figures (`pnum lnum`,
  so a narrow 1 does not leave a hole), -0.04 em tracking, `color.numeral`. Tabular figures (`tnum`)
  stay for numbers in lists, progress and the version selector, where columns must line up.
  **Fix the squashed Home numeral:** delete `ArtStage.Narrow = 0.7` (`UI/Controls/ArtStage.cs:348`),
  its glyph-advance multiply (`:405-410`), the width multiply (`:448`) and the
  `ScaleTransform(Narrow, 1)` in `Glyph.OnRender` (`:461-467`). Section 13 gives the sizing rule.
- **Sentence case** for every button and title ("Play", "Choose a version", "Install only").
  UPPERCASE only through `type.label`.
- Controls under 32 tall use 12 / 600 labels. Text never sits on art without a scrim.
- One weight step per hierarchy step. Never use 400 and 500 side by side to mean different things.

---

## 4. Spacing and layout

4 px base, 8 px rhythm.

| Token | px | | Token | px |
|---|---|---|---|---|
| `space.1` | 4 | | `space.6` | 24 |
| `space.2` | 8 | | `space.8` | 32 |
| `space.3` | 12 | | `space.10` | 40 |
| `space.4` | 16 | | `space.12` | 48 |
| `space.5` | 20 | | | |

- Related controls 8 apart. Groups 16. Sections 32. The page gutter is `space.6` (24) in the
  launcher, `space.8` (32) in the installer and `clamp(16px, 4vw, 56px)` on the website.
- Inside a control: icon-to-label gap 8 (6 at 28 tall). Padding per size is in section 5.
- **Content-fit rule:** a control's width = padding + content. Use `MinWidth` only to stop a
  short label from making a tiny primary button (dialog primary: 96). Play is the exception: a
  fixed width of 144 in every state (11.11). Use
  `MaxWidth` and truncate with an ellipsis for user text (account names: 160; profile
  names: 180). Never stretch a control to fill a row.
- Launcher window 1180 x 740 default, 960 x 600 minimum. Every layout here works at the minimum.

---

## 5. Control sizes

| Token | Height | Pad X | Icon | Radius | Label | Used for |
|---|---|---|---|---|---|---|
| `size.control.sm` | 28 | 10 | 14 | `radius.sm` 6 | 12 / 600 | chips, filter pills, inline actions, "All versions" back |
| `size.control.md` | 32 | 12 | 16 | `radius.sm` 6 | 13.5 / 600 | top bar (tabs, Store, account, icon buttons), menu items, toolbar dropdowns |
| `size.control.lg` | 36 | 14 | 16 | `radius.md` 8 | 13.5 / 600 | dialog and installer buttons, form inputs and dropdowns, settings controls, release rows |
| `size.control.xl` | 40 | 16 | 18 | `radius.md` 8 | 13.5 / 600 (Play: `type.action`) | the Home strip: version selector, profile, Play |

- Icon-only buttons are square: 28 / 32 / 36 / 40.
- `size.topbar` = 48 (holds `md` controls with 8 above and below).
- `size.switch` = 32 x 18 (knob 14). `size.checkbox` = 16. `size.progress` = 3 (rail),
  `size.dot` = 6, `size.badge` = 20 tall.
- Player heads: 24 in `md` (3x the 8 px face), 16 in `sm` rows (2x), 32 in drawers (4x), and
  12 GUI px in game. Always integer multiples of 8, nearest-neighbour, face plus hat layer.
- Minimum hit target is 28 with mouse. On the website's touch layouts it is 44 (controls grow,
  type does not).

---

## 6. Corner radii

| Token | px | Used for |
|---|---|---|
| `radius.xs` | 4 | badges inside cards, checkbox, art thumbnails in controls |
| `radius.sm` | 6 | controls 28-32 tall, menu items, tooltips, Settings tiles |
| `radius.md` | 8 | controls 36-40 tall, inputs, dropdown menus, the window (Win 11) |
| `radius.lg` | 12 | version cards, sheets, dialogs, the installer content card |
| `radius.full` | 999 | dots, switches, progress rails, single-line status badges |

- **Concentric rule:** anything inset inside a rounded container gets radius = outer radius -
  inset (minimum 2). For example, the account control (6) with a 4 px inset head gets head radius 2.
- **Full rounding** is only for things without an image edge. A container holding a square
  thing (a player head or an art thumbnail) is a rounded rectangle, never a pill.
- In game: 4 GUI px for title buttons 20 tall (the same 0.2 ratio as the launcher's 40 / 8).
  The Mod Menu keeps its own radii (section 15.1).

---

## 7. Lines and elevation

- Surfaces separate by **step and hairline**, not shadow: bg < surface < raised < hover, plus a
  1 px `color.line` edge on cards, or `lineStrong` on controls.
- `shadow.overlay` = `0 12px 32px rgba(0,0,0,.40)`. It is the only shadow, for menus, the
  version sheet, dialogs and toasts. In WPF, put it on a static parent border, never on an
  animating element. Effects re-rasterise in software.
- Retired: `Effect.OriginGlow` / `OriginGlowSoft` (jade glow), `Shadow.Raised` / `Shadow.Primary`
  on buttons, `Fill.PrimarySheen`, `Fill.Primary*` gradients, the Home promo `BlurEffect`.

---

## 8. States (one contract for every control)

| State | Primary | Secondary (raised) | Ghost / icon | Rows, cards, tabs |
|---|---|---|---|---|
| Rest | `accent` fill, `onAccent` text | `surfaceRaised` fill, `lineStrong` edge, `text` | transparent, `textDim` glyph or label | `surfaceRaised` + `line` (cards); transparent (rows, tabs) |
| Hover | `accentHover` | `surfaceHover`, edge `rgba(255,255,255,.14)` | fill `rgba(255,255,255,.06)`, glyph `text` | fill `surfaceHover`; card edge `lineStrong`, art brightness 1.06 |
| Press | `accentPress`, scale .97 | `surfacePress`, scale .97 | fill `rgba(255,255,255,.04)`, scale .97 | no scale (geometry of large targets never moves) |
| Focus (keyboard only) | 2 px `accentLine` ring, 2 px gap, radius + 2 | same | same | same |
| Selected | n/a | n/a | tab: fill `rgba(255,255,255,.07)` + `text` (the Mod Menu's tab) | row: `accentSoft` + check glyph in `accent`; card: 2 px `accent` ring at 2 px gap, nothing else changes (the badge keeps its installed count) |
| Disabled | `surfaceRaised` fill, `textMuted`, no edge | `textMuted`, `line` edge | `textMuted` at 60 % | 50 % opacity |
| Loading | width locked to the rest width: the label is hidden but keeps its space, a 14 spinner sits centred. Play is the one exception: progress text inside its fixed 144 (11.11) | same | spinner replaces glyph | skeleton fill `surfaceRaised`, no shimmer |
| Danger | n/a, never a filled red primary | `dangerSoft` fill, `dangerText` label, hover fill .22 | `dangerText` glyph on hover only (Quit) | |

- Disabled controls take no hover and show the default cursor. The Hand cursor appears on
  everything clickable, and on nothing else.
- Focus is `:focus-visible` / `IsKeyboardFocused`. Pointer clicks never draw the ring.
- Keyboard: Tab order follows reading order. Enter/Space activate. Esc closes the topmost
  overlay. Arrows move in menus, grids and lists. Type-ahead works in menus.

---

## 9. Motion

| Token | Launcher, installer, web | In game | Use |
|---|---|---|---|
| `motion.press` | 80 ms | 42 ms (`PRESS_MS`) | press scale and fill |
| `motion.hover` | 120 ms | 48 in / 64 out ms (`HOVER_IN_MS` / `HOVER_OUT_MS`) | fills, edges, glyph colour |
| `motion.base` | 160 ms | 120 ms (`DURATION_FAST_MS`) | dropdown open, tooltip, chevron turn, switch knob |
| `motion.enter` | 220 ms | 180 ms (`DURATION_MED_MS`) | sheets, dialogs, page content |
| `motion.exit` | 140 ms | 120 ms | anything closing (exit is always faster than enter) |
| `motion.scene` | 260 ms | n/a | page change, Home numeral crossfade, art crossfade start |
| `motion.reveal` | n/a | 560 ms (`StartupTransition.REVEAL_MS`) | loading screen to title |

- `ease.out` = `cubic-bezier(.2,.8,.2,1)` (default). `ease.in` = `cubic-bezier(.4,0,1,1)` (exits).
  In game, hover uses `1 - exp(-dt/tau)` smoothing, never per-frame steps.
- Movement is at most 4 px (menu open), 8 px (sheet enter) or 8 GUI px (title entrance rise,
  existing). Hover never moves or resizes a hit target.
- Reduced motion (`App.DisableMotion`, `prefers-reduced-motion`): every duration becomes 0,
  every state still shows.
- Nothing waits on an animation: launch, loading and page changes run at the speed of the
  real work.

---

## 10. Icons

- **One set:** Origin Line. These are the launcher's `Theme/Icons.xaml` 24 px masters (round caps
  and joins, outline only). The website and the in-game title use exports of the same
  geometry. The Mod Menu keeps its own line icons (protected; same style).
- **Stroke:** 1.7 px at every displayed size (WPF `Path` with `Stretch=Uniform` does not scale
  strokes; SVG uses `vector-effect: non-scaling-stroke`). In game: 1 physical px at GUI scale 2,
  baked per scale.
- **Sizes:** `icon.sm` 14 (28 controls), `icon.md` 16 (32-36 controls), `icon.lg` 18 (40
  controls), `icon.xl` 20 (in-game dock, at GUI px x scale).
- **No boxes:** an icon sits directly in its button, aligned to the label's cap centre, with 8
  between icon and label. Tiles exist only in labelled list rows (section 2.3).
- **Colour:** neutral (`textDim` at rest, `text` on hover) for actions. Destination icons (Store,
  Friends, dock destinations) use their fixed hue (section 2.3).

---

## 11. Components

### 11.1 Buttons

| Variant | Anatomy | Notes |
|---|---|---|
| Primary | fill `accent`, label `onAccent`, optional leading icon | One per decision area. Play is the primary at `xl`; dialogs use `lg` |
| Secondary | fill `surfaceRaised`, 1 px `lineStrong`, label `text` | Most actions |
| Ghost | no fill or edge, label `textDim` | Tertiary actions, "Cancel", "Install only" |
| Icon | ghost, square, glyph only, tooltip + accessible name required | Top bar, window controls, toolbars |
| Danger | `dangerSoft` fill, `dangerText` label | "Uninstall", "Remove profile". Never filled red |
| Window controls | icon 32, glyph 12 (minimise, close) | Close hover: `dangerSoft` fill, glyph `dangerText` |

Label text keeps its name through the flow: "Remove profile" leads to the toast "Profile removed".

### 11.2 Dropdowns

- **Trigger:** secondary button anatomy. Leading icon or thumb (optional), value in
  `bodyStrong`, chevron-down 12 in `textDim`, 8 gap before the chevron. Width = content.
  `md` in toolbars, `lg` in forms, `xl` in the Home strip.
- **Open trigger:** fill `surfaceHover`, the chevron turns 180 degrees (`motion.base`), and the
  edge becomes `accentLine`.
- **Menu:** `surfaceRaised` fill, 1 px `lineStrong`, `radius.md`, `shadow.overlay`, 4 px
  inner padding. Width = max(trigger, widest item), capped at 320. The menu opens 4 px
  below the trigger, left edges aligned, and flips above when there is no room. Enter:
  opacity 0 to 1 plus 4 px rise in `motion.base`.
- **Item:** `md` 32 tall, `radius.sm`, pad X 10, optional icon 16, label `body`, meta
  right-aligned in `caption` `textMuted`. Hover: `rgba(255,255,255,.06)`. Selected: check 14
  in `accent` on the right and label `bodyStrong`. Dividers: 1 px `line`, 4 px margins.
- Keyboard: Down/Up move, Enter selects, Esc closes and returns focus to the trigger, letters
  type ahead.

### 11.3 Version selector (two steps)

The Home version button opens a **sheet** under the top bar: `surface` at 98 %, `radius.lg`,
max width 960 (narrower windows: gutter 24), padding 24, `shadow.overlay`, page behind dims to
`color.scrim`. Enter `motion.enter` (8 px rise plus fade). Esc or a click outside closes it.

- **Step 1, families:** header `type.heading` "Choose a version" + `caption` "8 families, 26
  releases. Origin installs what each one needs." Close icon button (32) at the right. Then a
  4 x 2 grid of version cards (11.6), gap 12. Selecting a family goes to step 2 (crossfade
  `motion.scene`). Single-release families (1.18, 1.17, 1.16, 1.8) select directly and close.
- **Step 2, releases:** ghost `sm` "All versions" with back chevron. Left column (320): the family
  card at large size + description in `body` `textDim`. Right: list of releases, rows `lg` 36,
  `radius.sm`, version in `bodyStrong` `tnum`, status in `caption` ("Installed" with a 6 px
  `success` dot, or "Downloads when you press Play" in `textMuted`), selected row `accentSoft`
  + check. Every release of the family is listed, newest first, never truncated (1.21 has 10
  rows; the list scrolls inside the sheet past 10). Footer, right-aligned: ghost `lg` "Install
  only", primary `lg` "Play 1.21.11".
- The selection writes `LauncherSettings.SelectedVersion` through `SettingsStore.Update`
  only. Installed state comes from a disk check off the UI thread. Until it returns, the
  status shows nothing (no placeholder text).

### 11.4 Top bar (fixed, translucent)

- 48 tall, full window width, `color.nav` with a 1 px `color.line` bottom hairline. No blur. It
  stays fixed while pages scroll under it (it is already a sibling of `PageHost`). It is the
  window's drag area (the controls in it are not).
- **Layout is a row, not a 3-column grid:** left group (pad 12), tabs, right group (pad 8). Both
  groups keep their natural width. The tabs take the space left between them and centre in it,
  with **at least 24 px** from each group. They never overlap and never touch either group.
- **Left, brand lockup = the Website link:** logo 24, 6 gap, wordmark 16, inside a ghost hit area
  32 tall (`radius.sm`, pad 4 / 6). Hover: fill `rgba(255,255,255,.06)` and a 12 px external
  arrow in `textMuted` fades in after the wordmark; tooltip "originclient.org". Click opens
  `https://originclient.org` through `Core.Promos.ExternalLink.Open`. Settings > About lists the
  Website and the Store again, so neither link depends on the bar alone.
- **Centre:** tabs Home, Mods, Profiles, Settings. `md` 32, pad X 12, `type.label`, gap 4. Rest
  `textDim`, hover `text` + `rgba(255,255,255,.04)`, selected `text` + `rgba(255,255,255,.07)`
  fill (the Mod Menu's own selected tab), `radius.sm`.
- **Right (pad 8):** [update chip, only while an update waits] Store, Import (icon), Friends
  (icon + count badge), divider 1 x 20 at `rgba(255,255,255,.14)` (8 margins), account control, 8 gap,
  minimise, close. Gaps 4 inside the group.
- **Narrow windows give up width in this order** (window width):
  1. 1100 and wider: everything at full size.
  2. Below 1100: Store becomes an icon button 32 (gold bag only, tooltip "Store"). The label and
     the arrow go. The update chip becomes an icon button 32 at the same moment (below).
  3. Below 1000: the wordmark hides; the logo stays as the Website link.
  4. The account name ellipsizes at 160 at every width.
- **Update chip** (first in the right group, only while a downloaded update waits): ghost `md`
  32, `radius.sm`, pad X 10, fill `hue.gold` at 12 % (hover 20 %), download glyph 16 + "Update"
  in `bodyStrong`, both `hue.gold`, gap 6. Tooltip "Restart Origin to install the update";
  accessible name "Update ready: restart to install". Click restarts into the installed update
  (updates are mandatory, so it never offers "later"). **Below 1100** it is an icon button 32:
  the download glyph in `textDim`, a 6 px `hue.gold` dot at its top-right (5 in, 2 px `bg` ring),
  same tooltip and name. It never hides at any width.
- **Website link below 1000:** only the logo shows, so the link's accessible name is always
  "Open originclient.org" (WPF `AutomationProperties.Name`; the logo image itself is
  decorative), with the same tooltip. Settings > About is the visible, labelled route to the
  Website at every width.
  Measured in the preview with the update chip showing (the widest right group): 232 / 232 px
  either side of the tabs at 1440, 102 / 102 at 1180, 101 / 101 at 960, and 54 / 54 at 960 with
  a 23-character name. Without the chip: 279, 149, 119 and 72. The window minimum is 960.

### 11.5 Account pill

- A rounded rectangle, not a pill: `md` 32 tall, `radius.sm` 6 (the 28-32 control rule), fill `rgba(255,255,255,.04)`, 1 px
  `line`. Hover `surfaceHover` with `lineStrong`.
- Anatomy: 4 inset, **head 24 x 24 at radius 2** (concentric: 6 - 4), 8 gap, name in
  `bodyStrong` (max 160, ellipsis), 6 gap, chevron-down 12 `textDim`, 10 right pad.
- The head is the skin's face plus hat layer at exactly 3x, nearest-neighbour, with no border and
  no circle mask. A missing skin shows the default Steve face, never an empty box.
- Signed out: ghost `md` "Sign in" with the account icon. Offline test mode (dev builds only): the
  name reads "Offline" with a `warning` dot at the head's bottom-right (6 px, 2 px `nav` ring).
- Opens the account drawer (right, 380, `surface`).

### 11.6 Numerals on art: the ground-line rule (Home and every card)

Will: the large version number must feel like an intentional part of the image composition. One
rule places every version number set on art, the Home numeral and every version card alike. The
number stands on the picture's ground, in its darkest corner, at a size taken from the picture:

- **Corner:** bottom-left, always. A radial `color.cornerShade` grows from that corner (cards:
  ellipse 100 % x 80 %; Home: 64 % x 70 %), `color.bg` from 88 % at the corner, 50 % at 45 %, 0 at
  the edge. Cards add a whole-card dim of 6 %. The rest of the picture stays bright.
- **Gutter:** the first figure's **ink** (not its text box) sits on the gutter: 12 on a card,
  24 on Home. Compensate the first glyph's left side bearing (WPF: `GlyphTypeface` left side
  bearing x size; web: canvas `actualBoundingBoxLeft`).
- **Ground line:** the figures' baseline. 12 above the card's bottom edge; on Home, 24 above the
  top of the control strip (88 from the window's bottom at 1180 x 740).
- **Size:** figure height (cap height) = **25 % of the art's height**. Inter's figures are
  0.7275 em, so font size = art height x 0.344. Card 16:10: font = 21.5 % of the card's width.
  Home 1180 x 740: figures 185, font 254. Home 960 x 600: figures 150, font 206.
- **Width cap:** the ink is never wider than 2/3 of the art; past that the font shrinks to fit.
  Measured at font 254: 26.3 is 522 px (44 % of 1180), the longest key 1.21.10 is 748 (63 %), so
  nothing shrinks at the default window.
- **Figures:** `type.numeral`: Inter 800, natural width (no horizontal squash), proportional
  lining figures, -0.04 em, `color.numeral` neutral white, never tinted, no glow. A 1 px
  legibility shadow on bright art, drawn, never an effect: `ArtStage.OnRender` draws the same
  `FormattedText` once at +1 px y in black at 40 %, then the white text on top. No
  `DropShadowEffect` (section 7: no effects on animating elements, and the numeral crossfades,
  section 13). The preview's CSS `text-shadow` stands in for this OnRender pass.
- **Eyebrow:** the update name in `type.label` caps, `color.onArt`, above the figures' cap line
  by max(8, figures x 0.08), same left edge (Home: `type.labelLg`, 15 gap).
- **Range** (cards only): "1.21 - 1.21.11" in `caption` `color.onArt`, 12 after the figures, on
  their baseline. Hidden on cards narrower than 220.
- **Status badge** (cards) top-left at 10 / 10: `badge` (20 tall, `radius.full`, `surface` at 80 %,
  `caption`, 6 px dot): "2 of 10 installed" (`success` dot) or "Not installed" (muted dot).
- **Card states:** hover edge `lineStrong` + art brightness 1.06 (no scale). Selected: 2 px
  `accent` ring at 2 px gap and nothing else changes, so the badge keeps its count. Focus: the
  same ring in `accentLine`.
- Never bake the number into the art files. Short keys ("26") and long ones ("1.21.11") use the
  same rule; only the width cap can shrink a long one.

### 11.7 Store entry

- Lives in the top bar's right group, first item: ghost `md` 32, bag icon 16 in `hue.gold`,
  label "Store" in `bodyStrong` `textDim`, a trailing external-arrow glyph 12 in `textMuted`
  (it opens the browser). Hover: label `text`, fill `rgba(255,255,255,.06)`.
- Opens `https://originclient.org/store` through `Core.Promos.ExternalLink.Open` (the Store
  page's live URL, `services/cosmetics/src/stripe.js:31` `STORE_PAGE`). It takes the Website
  button's slot (`MainWindow.xaml:115-126`); the Website link moves to the brand lockup (11.4)
  and Settings > About. The Website link is never dropped.
- Below 1100 px of window width it is an icon button 32 (bag only, tooltip "Store").
- It is never a card, never in the Play strip, and has no promo art. Discovery comes from the
  gold glyph and a constant position.

### 11.8 Switch, checkbox, input, tooltip

- **Switch:** 32 x 18 track `radius.full`. Off: `rgba(255,255,255,.10)` track, knob `text` at 70 %. On:
  `accent` track, knob `onAccent`. Knob 14, travel `motion.base`. Label left in `caption` `textDim`.
- **Checkbox:** 16, `radius.xs`, 1 px `lineStrong` edge. Checked: `accent` fill + check 12
  `onAccent`. Label `body` 8 to the right. The whole row is the hit target.
- **Input:** `lg` 36, `color.field` fill, 1 px `lineStrong`, `radius.md`, pad X 12, `body`,
  placeholder `textMuted`. Focus: edge `accentLine` (no extra ring). Error: edge `danger` +
  `caption` `dangerText` below.
- **Tooltip:** `surfaceRaised`, 1 px `lineStrong`, `radius.sm`, pad 6 x 8, `caption`, max 240, 300 ms
  delay, 8 px from the target, kept inside the window.

### 11.9 Badges and status dots

- Dot 6 `radius.full`: `success` ready / installed, `warning` attention, `danger` failed, `accent`
  active (running, online), muted not installed.
- Badge 20 tall, `radius.full`, pad X 8, `caption` 500: neutral (`rgba(255,255,255,.06)`, `textDim`),
  or tinted (hue 16 % fill, hue text). Count badge on icons: 16 tall, min 16, `accent` fill,
  `onAccent` 10.5 / 700, top-right offset -4 / -4.

### 11.10 Dialogs and toasts

- Dialog: `surface`, `radius.lg`, pad 24, max width 440, `shadow.overlay`, scrim `color.scrim`.
  Title `type.title`, body `body` `textDim`, footer right-aligned (ghost then primary or
  danger, both `lg`). Default focus is the safe action.
- Toast: bottom-centre, 8 above the strip, `surfaceRaised` + `lineStrong`, `radius.md`, `md` height
  plus padding, `caption` text, optional ghost action ("Undo"), 5 s.

### 11.11 Loading indicators

- **Progress rail (the one progress element):** 3 tall, `radius.full`, track
  `rgba(255,255,255,.08)`, fill `accent`, width animated with `motion.base`. Used by Play,
  the installer, the uninstaller and the loading screen (the in-game rail is the same
  token, see section 15.3).
- **Indeterminate:** only while no real number exists. A 28 % segment slides across in 1.2 s
  `ease.inOut`. There is never a fake percentage.
- **Spinner:** 14 (12 in `sm`), 1.7 stroke 270-degree arc in `textDim`, 0.8 s linear. Only inside
  controls waiting on the network (sign-in, Fix compatibility).
- **Play while preparing:** the button keeps its fixed 144 width. Fill becomes `surfaceRaised` + `lineStrong`,
  the label reads "Preparing 42 %" (`bodyStrong`, `tnum`), and a progress rail runs inside the
  bottom edge (8 inset, 6 from the bottom). On hover the label becomes "Cancel". The status line
  above Play names the step ("Downloading libraries, 128 of 412 MB"). While the game runs, the label
  is "Playing" with an `accent` dot, disabled.

---

## 12. Backgrounds

- **Launcher Home:** the selected family's colour art (`-c{1280|2560|3840}.jpg`, chosen by
  window width x DPI). The slow drift stays only if Phase 3 measures it inside the frame budget
  (it re-composites the full window at 15 fps); otherwise the art is static. Bottom scrim:
  `color.bg` from 82 % at the window's bottom edge to 0 at 200 px above the strip. There is no top
  scrim, since the bar is a solid surface. The art crossfades in `motion.scene` when the family
  changes.
- **Other launcher pages:** `color.bg` with the same art under 92 % `color.bg` (the existing
  `PageDim`, re-valued), so the version still tints the room faintly.
- **Sheets and dialogs:** `color.scrim` over whatever is behind.
- **Installer:** flat `color.bg`. No art.
- **Loading screen:** flat `color.bg` (replaces `CLEAN_BG #0B0C0F`), the same colour as the
  launcher's launch cover, so the hand-off from launcher to game is seamless.
- **In-game title:** the version's own panorama, blurred (1.20.5+; dim only before), then
  `rgba(14,17,16,.35)` dim (`CLEAN_DIM_TITLE` re-valued to the `bg` hue), vignette kept.
- **Website:** `color.bg`, with the island scene as the hero's art.

---

## 13. Launcher Home layout

```
+-- top bar 48 --------------------------------------------------------------------+
| [logo][wordmark]>   |24|  HOME  MODS  PROFILES  SETTINGS  |24|  Store i f | (h) Name v  - x |
+----------------------------------------------------------------------------------+
|                                                                                  |
|  (corner shade grows from the bottom-left)                                        |
|                                                                                  |
|  THE LATEST RELEASES                 <- eyebrow, 15 above the cap line            |
|  2 6 . 3                             <- figures 185 = 25 % of 740, ink on gutter 24 |
|  ----------------------------------- ground line, 24 above the strip              |
|                                                                   * Ready to play |
| [thumb 26.3  The Latest Releases v] [o No profile v]          My mods [=] [> Play] |
+---------------------------------------------- 24 inset ---------------------------+
```

- **Strip:** one row of `xl` 40 controls, 24 from the left, right and bottom edges. Left group:
  the version selector (thumb 32 x 24 `radius.xs`, numeral `bodyStrong` 700 `tnum`, update name in
  `caption` `textDim`, chevron), 8, then the profile dropdown. Right group: "My mods" switch (label
  + 32 x 18 switch), 16, then Play (**fixed width 144** in every state, play glyph 14 +
  `type.action`). The status line (`caption`, 6 px dot) sits 8 above Play, right-aligned.
- **Numeral:** the selected release ("26.3") placed by the ground-line rule (11.6), the same rule
  as the cards. At 1180 x 740: font 254, figures 185 tall (25 % of 740), ink 522 wide (44 % of
  1180), first figure's ink at x 24, baseline 88 above the window's bottom, eyebrow "THE LATEST
  RELEASES" 15 above. At 960 x 600: font 206, figures 150. Ink width capped at 2/3 of the window
  (11.6). Replaces the fixed 0.42 x height and the centred band at `ArtStage.cs:422`. One
  `FormattedText` per change, not per glyph per resize. Crossfades with the art (`motion.scene`).
- At 960 x 600 the update name in the version selector drops first, then the "My mods" label
  (the switch stays, with a tooltip).

---

## 14. Installer and uninstaller (Origin Setup)

- **Window:** 640 x 440, fixed, chromeless, `color.bg`, Win 11 rounded corners (DWM, 8).
  Title bar 40: logo 20 + "Origin Setup" (`bodyStrong`) left at 16, ghost icon buttons
  minimise and close (32) right at 4. The bar is the drag area. Content gutter 32. Footer: 32
  from the bottom, buttons right-aligned, `lg` 36, 8 apart.
- **No wizard steps.** Each flow is one screen with states. **Every state keeps the hero** (logo
  48 + wordmark 24, stacked, 8 below the title bar) and starts its content in the same place
  under it, top-aligned. The footer stays pinned 32 from the bottom. Nothing is centred
  vertically, so no state floats in an empty window.
  - **Ready:** logo 48 + wordmark 24 stacked. Then `type.display` "Install Origin", `body` `textDim`
    one line, then a location field (input `lg` with a ghost "Change" inside on the right, the
    path ellipsized in the middle), then checkboxes "Desktop shortcut" and "Start menu shortcut"
    (both on), then the footer: ghost "Cancel" and primary "Install".
  - **Installing:** `type.display` "Installing Origin", `body` `textDim` "This takes about a minute.
    You can keep using your PC.", progress rail full content width (24 below), `caption`
    step text ("Copying files, 128 of 412"), and the footer: ghost "Cancel". Real progress only.
  - **Done:** `type.display` "Origin is installed", `body` one line, a `success` status line, then
    the footer: ghost "Close" and primary "Open Origin".
  - **Error:** `type.display` naming what failed, a `danger` status line, `body` with the
    fix, footer: ghost "Close", secondary "Copy details", primary "Try again".
- **Uninstaller, Confirm:** `type.display` "Uninstall Origin?", `body` "Origin and its shortcuts are
  removed.", then the checkbox "Also delete my worlds, settings and accounts" **off by default**
  (`body`, `dangerText` only when checked), then the footer: ghost "Keep Origin" (default focus)
  and danger "Uninstall". Removing: rail + step text. Done: "Origin was removed" + where kept
  data lives (`caption`, path) + "Close".
- Update setup (self-update) uses the Installing state with "Updating Origin".

---

## 15. In game

### 15.1 Protected: the Origin Mod Menu (never changes)

Values read from `OriginModMenuScreen.java` and `OriginDeck.java` (1.21.1, identical in all 20
modules). Design px = 1 screen px at 1920 x 1080 (`OriginDeck.scale`).

| Protected token | Value |
|---|---|
| Panel | 920 x 580, radius 8, `0xF0141816` (#141816 at 94 %), edge `0x12FFFFFF` |
| Margin / gap | `M` 24, `GAP` 16, header `HEAD_H` 64, toolbar `BAR_Y` 80 (32 tall), body `BODY_Y` 128 |
| Card / row / option | `CARD_H` 184 radius 8; `ROW_H` 44 radius 7; `OPT_H` 40; option buttons 30 tall radius 5 |
| Tabs | 30 tall, radius 6, selected fill `0x12FFFFFF`, hover `0x0AFFFFFF`, labels tracked caps |
| Toolbar controls | 32 tall, radius 6, rest `0x0DFFFFFF`, hover `0x14FFFFFF`; search 176 wide, field `0x47000000` |
| Colours | `CARD #1D2320`, `CARD_H #232A26`, `LINE 0x0FFFFFFF`, `LINE2 0x1AFFFFFF`, `TEXT #F1E9D2`, `DIM 0xADF1E9D2`, `MUTE 0x6BF1E9D2`, `ACC #5FD09B`, `ACC_H #83E2B5`, `ACC_D #2E7A57`, `ACC_SOFT 0x245FD09B`, `ON_ACC #0A1710`, `RED #D9675A`, `RED_TEXT #F98E80`, `GOLD #E9C46A`, `GOLD_BG #3A3220` |
| Type | Inter 400/600 MSDF; header "ORIGIN CLIENT" 18; card names 13.5; option labels 10.5; caps tracking `CAPS_TRACK` 2.4 |
| Motion | `HOVER_IN_MS` 48, `HOVER_OUT_MS` 64, `PRESS_MS` 42 |

The system's tokens were chosen to equal these where they overlap (raised, hover, lines, text
alphas, accent family, danger, gold, 6/8 radii, 32 toolbar controls), so the Mod Menu sits inside
the product without a seam.

### 15.2 Main menu (title), 1.21.1 first

Keep `TitleLayout` geometry and order (MAIN_W 200, BUTTON_H 20, ROW_STEP 24, DOCK 20, DOCK_GAP 4,
DOCK_BOTTOM 14, MARK 34, MARK_ABOVE 60): player chip, logo + wordmark, Singleplayer,
Multiplayer, Options | Mods, dock, copyright. **Only the skin changes:** vanilla sprites become
Workbench panels drawn with the existing SDF panel (1.21.1) or baked masks (others).

| Element (GUI px) | Token mapping |
|---|---|
| Button 200 x 20 (pair 98 x 20) | fill `surfaceRaised` at 82 % (`0xD11D2320`), 1 GUI px `lineStrong` edge, radius 4. Label Inter 600 with a 6 GUI px cap height (about 8.25 GUI px font size), `color.text`, optional 8 GUI px leading icon in `textDim` |
| Hover / press / focus / disabled | `surfaceHover` at 90 % + edge `0x24FFFFFF` / `surfacePress` / 1 GUI px `accentLine` ring / label `textMuted`, fill at 60 % |
| Dock (Friends, Cosmetics, Language, Accessibility, Quit; Realms first on 26.3 only) | **bare glyphs, no squares**: 20 x 20 hit, glyph 12 GUI px in its hue (sky, gold, gold, emerald, coral) at 85 %. Hover: a `rgba(255,255,255,.08)` radius 4 plate appears behind it and the glyph goes to 100 %. Tooltip with the name |
| Player chip | 16 GUI tall, radius 4, fill `rgba(255,255,255,.06)`; head 12 GUI px (radius 2), name Inter 600 `text`; the same anatomy as the launcher's account pill |
| Logo + wordmark | real `origin_logo.png` at MARK 34, wordmark export at 11 GUI px tall, 6 GUI px gap |
| Background | blurred panorama + `0x590E1110` dim + existing vignette |
| Footer line | "Minecraft 1.21.1, Origin" left and copyright right, Inter 400 `textMuted`, 6 GUI px from the bottom |
| Entrance | existing 8 GUI px rise + 55 ms stagger (TITLE_ENTRANCE_MS 1200) |

"Mods" opens `OriginModMenuScreen` directly (unchanged). Fail-soft: if a panel draw fails, the
button falls back to vanilla for that session (`OriginButtonRenderer.broken`), never a crash.

### 15.3 Loading screens

- `color.bg` fill. The real logo 72 design px (x window height / 1080, min 0.75) is centred at 44 %
  of the height. The progress rail (200 x 3 design px, `accent` fill, `rgba(255,255,255,.08)`
  track) sits 24 below it. A `caption` status line (Inter 400, `textDim`) sits 12 below the rail,
  showing the real stage name, with a percentage only when Minecraft reports one.
- The same layout serves resource load, world load, connecting and terrain. Only the caption
  text changes.
- To title: the logo fades and moves to the title's MARK position over `motion.reveal` (560 ms) while
  the title rises in. The logo image is moved, never morphed (the ring-texture morph animation
  from commit 7520b2f7b is retired; it drew a recreation).
- Cost budget: one textured quad, two rects, one text line per frame. No shaders, no blur.

### 15.4 1.8.9 (Forge 11.15.1.2318 + OptiFine) constraints

- `gui/OriginTitleScreen.java` is a hand-made replacement, not a mixin, so every title change is
  re-done by hand there. There is no Accessibility square (the screen does not exist in 1.8.9).
  Language stays.
- Fixed-function GL: no SDF shader. Rounded panels use **baked corner masks** at physical
  resolution, blitted 1:1 (four corners + three rects per panel). No per-pixel AA, no blur:
  the panorama gets the dim only (dim at 50 % instead of 35 % to make up for the missing blur).
- The logo uses the `origin_logo-64.png` / `-128.png` exports (no mipmaps in the GUI path, so a
  256 to 34 GUI px minify would alias), `GL_LINEAR`.
- Text through `OriginTtfFont` (Inter), the same sizes in GUI px.
- Loading: the Forge splash runs before Origin can draw. Origin's `OriginLoadingRenderer`
  covers what follows (connect, terrain, world load). The Forge splash itself can take Origin
  colours and the real logo through `config/splash.properties` (`background`, `bar`, `barBackground`,
  `font`, `logoTexture`). This is **likely, to be verified** against 11.15.1.2318's `SplashProgress`
  in Phase 4, at zero runtime cost.
- Budget: the title and loading screens add no measurable frame time (Phase 6 benchmark).

### 15.5 Other versions

1.21.x and 26.x share the 1.21.1 mixin with era hooks. The pre-1.20 modules draw through their
per-module `Gfx` and are `.no-shared-sync` (manual edits per module). 1.21.6+ (deferred GUI) use
baked masks, not per-pixel AA panels (about 130 draws each, see MEMORY "deferred-GUI draw-call
cost"). The look must be the same everywhere; the method may differ per version (mandate 5).

---

## 16. Website

- Mode: persuade. It keeps its island-in-the-sky hero, takes the palette, type, radii, button and
  badge rules above, and uses dark only.
- The hero download CTA may be `size.control.hero` 48 (the only control above 40 in the system).
  Touch layouts keep a 44 minimum target. Section spacing is in multiples of `space.12` (48).
- Cards use `radius.lg` 12 (today `--r` is 20, too round for the system).

---

## 17. Token mapping

Status: **reuse** = the key exists, so only its value changes; **new** = the key is added in Phase 3/4;
**retire** = the key is deleted after its users move.

### 17.1 Launcher (`src/OriginLauncher.App/Theme/*.xaml`) and installer (`src/OriginSetup/Theme/Setup.xaml`, same keys)

| Token | XAML key (Color.X has a matching Brush.X) | Value | Status |
|---|---|---|---|
| color.bg | `Color.Bg` | `#FF0E1110` | reuse (was #111111) |
| color.bgSunken | `Color.BgAlt` | `#FF0B0D0C` | reuse |
| color.surface | `Color.Panel` | `#FF171C19` | reuse |
| color.surfaceRaised | `Color.Card` (+ `Color.RaisedBody`) | `#FF1D2320` | reuse |
| color.surfaceHover | `Color.CardAlt` (+ `Color.RaisedHover`) | `#FF232A26` | reuse |
| color.surfacePress | `Color.SurfacePress` | `#FF191E1B` | new |
| color.nav | `Color.PanelGlass` | `#E0141816` | reuse |
| color.field | `Color.Field` | `#47000000` | new (replaces `Brush.Inset`) |
| color.line | `Color.SoftStroke` | `#0FFFFFFF` | reuse |
| color.lineStrong | `Color.Stroke` | `#1AFFFFFF` | reuse |
| color.text | `Color.Text` (+ `Color.OnPhoto`) | `#FFF1E9D2` | reuse |
| color.textDim | `Color.TextDim` | `#ADF1E9D2` | reuse |
| color.textMuted | `Color.TextMuted` | `#85F1E9D2` | reuse |
| color.accent / hover / press | `Color.Accent` / `AccentHover` / `AccentPress` | `#FF5FD09B` / `#FF83E2B5` / `#FF4CB985` | reuse (was neutral #E0E0E0) |
| color.accentSoft / accentLine | `Color.AccentSoft` / `Color.AccentBorder` | `#245FD09B` / `#995FD09B` | reuse |
| color.onAccent | `Color.TextOnAccent` | `#FF0A1710` | reuse |
| color.success / warning / info | `Color.Success` / `Warning` / `Info` | `#FF68C490` / `#FFE9C46A` / `#FF6FAEE8` | reuse |
| color.danger / dangerText / dangerSoft | `Color.Danger` / `Color.DangerText` / `Color.DangerSoft` | `#FFD9675A` / `#FFF98E80` / `#24D9675A` | reuse / new / new |
| color.scrim | `Brush.Scrim` | `#A30A0D0C` | reuse |
| page dim | `Brush.PageDim` | `#EB0E1110` | reuse |
| numeral ink | `Brush.NumeralInk` | `#FFF5F5F5` | re-value (neutral white, was parchment) |
| progress track | `Brush.ProgressTrack` | `#14FFFFFF` | reuse |
| hue.* | `Color.Hue.Emerald/Sky/Gold/Lilac/Coral` (+ `Brush.HueTile.*` at 16 %) | section 2.3 | new (replace `Tint.*`, `TintInk.*`) |
| family hue | `Brush.Acc`, `FamilyTheme.cs` table | none | retire (colour lives in hues, not per family) |
| space.1-12 | `Space.1` 4, `Space.2` 8, `Space.3` 12, `Space.4` 16, `Space.5` 20, `Space.6` 24, `Space.8` 32, `Space.10` 40, `Space.12` 48 | | reuse 1-2, renumber the rest (old 3=16, 4=24, 5=32, 6=48) |
| size.control.sm/md/lg/xl | `Size.ControlSm` 28, `Size.ControlMd` 32, `Size.ControlLg` 36, `Size.ControlXl` 40 | | reuse/new (old Sm 32, Control 40, Lg 48) |
| size.topbar | `Size.Bar` | 48 | reuse (was 56) |
| size.icon | `Icon.SizeSmall` 14, `Icon.Size` 16, `Icon.SizeLarge` 18 | stroke 1.7 | reuse / new |
| radius.xs/sm/md/lg | `Radius.Xs` 4, `Radius.Sm` 6, `Radius.Md` 8, `Radius.Lg` 12 | | new; `Radius.Control/Play/Dialog/Card/Row/Chip/NavItem/Swatch` re-pointed then retired |
| motion | `Motion.Press` 0.08, `Motion.Hover` 0.12, `Motion.Base` 0.16, `Motion.Scene` 0.26 (enter 0.22), `Motion.Exit` 0.14; `Ease.Out` (.2,.8,.2,1) | | reuse (re-valued) |
| shadow.overlay | `Shadow.Overlay` (BlurRadius 32, ShadowDepth 12, Opacity .40) | | new; `Shadow.Raised/Primary/Card` retire |
| type.* | `FontSize.Label` 11, `Caption` 12, `Body` 13.5, `Title` 16, `Action` 16, `Heading` 22, `Display` 32; styles `Text.Label/Caption/Body/BodyStrong/Title/Heading/Display` | | reuse/re-valued; `Poster`, `PlayWord`, `PillNumber`, `LaunchCount` retire |
| brand | `Brand.Logo`, `Brand.Wordmark`, `Size.Logo.Bar` 24, `Size.Wordmark.Bar` 16 | | new; `Effect.OriginGlow*` retire |

### 17.2 Website (`website/css/site.css` `:root`)

| Token | CSS variable | Value | Status |
|---|---|---|---|
| color.bg | `--bg` | `#0e1110` | reuse (was #0a0a0b) |
| color.surface | `--panel` | `#171c19` | reuse |
| color.nav | `--glass` | `rgba(20,24,22,.88)` | reuse |
| color.surfaceRaised / Hover | `--card` / `--card-alt` | `#1d2320` / `#232a26` | reuse |
| color.line / lineStrong | `--soft` / `--stroke` | `rgba(255,255,255,.06)` / `.10` | reuse (stroke was .24) |
| color.text / dim / muted | `--text` / `--dim` / `--muted` | `#f1e9d2` / `rgba(241,233,210,.68)` / `.52` | reuse |
| color.accent / hover / press | `--acc` / `--acc-h` / `--acc-p` | `#5fd09b` / `#83e2b5` / `#4cb985` | reuse (was neutral) |
| accentSoft / accentLine / onAccent | `--acc-soft` / `--acc-border` / `--on-acc` | `rgba(95,208,155,.14)` / `.6` / `#0a1710` | reuse |
| color.danger | `--danger` (+ `--danger-text`) | `#d9675a` (`#f98e80`) | reuse / new |
| success / warning / info | `--success` / `--warning` / `--info` | section 2.1 | new |
| hue.* | `--hue-emerald` ... `--hue-coral` | section 2.3 | new |
| radius.* | `--r-xs` 4, `--r-sm` 6, `--r-md` 8, `--r-lg` 12 (`--r` = `--r-lg`) | | new / re-valued |
| size.control.* | `--h-sm` 28, `--h-md` 32, `--h-lg` 36, `--h-xl` 40, `--h-hero` 48 (`--bh`) | | new |
| space.* | `--s1` ... `--s12` | | new |
| motion | `--dur-press` 80ms, `--dur-hover` 120ms, `--dur-base` 160ms, `--dur-enter` 220ms, `--dur-exit` 140ms; `--ease` `cubic-bezier(.2,.8,.2,1)` | | reuse (re-valued) |

The preview page's `:root` block is a working example of this set.

### 17.3 In game (`theme/OriginTheme.java`, per module; 1.21.1 first)

| Token | Java constant | Value | Status |
|---|---|---|---|
| color.bg | `OriginTheme.BG` (DEF_BG) and `CLEAN_BG` | `0xFF0E1110` | reuse; `CLEAN_BG` re-valued from `0xFF0B0C0F` |
| color.surface | `PANEL` | `0xFF171C19` | reuse |
| color.surfaceRaised / Hover / Press | `BOX_FILL` / `BOX_FILL_HOVER` / `BOX_FILL_PRESS` | `0xD11D2320` / `0xE6232A26` / `0xE6191E1B` | reuse (re-valued) / new |
| color.lineStrong | `BOX_BORDER` | `0x1AFFFFFF` | reuse (was 0xF0080B09) |
| hover edge | `BOX_BORDER_HOVER` | `0x24FFFFFF` | reuse |
| color.text / dim / muted | `TEXT` / `TEXT_DIM` / `MUTED` | `0xFFF1E9D2` / `0xADF1E9D2` / `0x85F1E9D2` | reuse (dim and muted move to text alphas) |
| color.accent family | `ACCENT`, `ACCENT_2`, `ACCENT_SOFT`, `ACCENT_BORDER` | as 2.1 | reuse |
| danger / warning / success / info | `DANGER` / `WARNING` / `SUCCESS` / `INFO` | `0xFFD9675A` / `0xFFE9C46A` / `0xFF68C490` / `0xFF6FAEE8` | reuse (danger, warning re-valued to the Mod Menu's) |
| hue.* | `HUE_EMERALD`, `HUE_SKY`, `HUE_GOLD`, `HUE_LILAC`, `HUE_CORAL` | as 2.3 | new |
| title dim / loading dim | `CLEAN_DIM_TITLE` / `CLEAN_DIM_LOADING` | `0x590E1110` / `0x990E1110` | reuse (re-valued to the bg hue) |
| progress | `CLEAN_BAR_TRACK` / `CLEAN_BAR_FILL` | `0x14FFFFFF` / `0xFF5FD09B` | reuse (fill was white) |
| radius | `RADIUS_SM` 1, `RADIUS_MD` 2, `RADIUS_LG` 3, `RADIUS_CONTROL` 4 (GUI px) | | reuse / new |
| motion | `HOVER_IN_MS` 48, `HOVER_OUT_MS` 64, `PRESS_MS` 42, `DURATION_FAST_MS` 120, `DURATION_MED_MS` 180 | | reuse (unchanged) |
| title geometry | `TitleLayout` constants | unchanged | reuse |
| Mod Menu | `OriginDeck.*`, `OriginModMenuScreen` constants | section 15.1 | **protected** |

`shared/.../OriginTheme.java` is a stale copy. 1.21.1's fork is the truth. Phase 4 updates the
shared copy and runs `tools/shared-sync/sync.py`. Modules marked `.no-shared-sync` get the same
values by hand.

---

## 18. Must not look like

- Lunar or Feather: neon edges, glass blur everywhere, glowing accents.
- An AI dashboard: bento grids of stat tiles, gradient cards, oversized rounded containers.
- An esports or gamer UI: Orbitron, Michroma, italic speed type, angled cuts.
- A Discord clone: a left server rail, blurple, round avatars in a column.
- A generic Windows utility: grey system dialogs, Segoe UI, square wizard steps.
- Retro pixel kitsch: dirt textures, 8-bit fonts on launcher surfaces, stone buttons.

---

## 19. Open questions for Will

1. **In-game wordmark.** Twenty modules ship a different `wordmark.png` (1540 x 524, spaced
   letters), and three ship a third variant. Per your rule, Phase 4 replaces them with exports of
   the launcher wordmark. Confirm the in-game copies are not an official variant.
2. **Logo on light surfaces.** The master is white. In the (future) light theme, the plan shows
   it through its own alpha filled with the text colour. The shape is unchanged, but the colour
   differs. The alternative is to keep the top bar dark in the light theme.
3. **Website palette.** The site moves from neutral grey with a white accent to Workbench (moss
   black, parchment, emerald CTA). This follows "one identity", but it is the biggest visible
   change on the site.
