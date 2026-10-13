# Style

Every value here is lifted from the launcher
(`src/OriginLauncher.App/Theme/{Colors,Typography,Spacing,Buttons,Inputs,Brand}.xaml`)
and lives in the `:root` of `website/css/site.css`. Use the tokens; never retype a hex.

## Colour: monochrome + one accent

| Role | Token | Value |
|---|---|---|
| Page | `--bg` / `--bg-alt` | `#0e1110` / `#121614` |
| Panel | `--panel` / `--panel-el` | `#171c19` / `#202720` |
| Glass (cards over the 3D) | `--glass` | `rgba(23,28,25,.92)` |
| Card / hover | `--card` / `--card-alt` | `#1d2420` / `#27302a` |
| Lines | `--soft` / `--stroke` | `#2e3b33` / `#4e705c` |
| Text | `--text` / `--dim` / `--muted` | `#f1e9d2` / `#b8b5a6` / `#7d8279` |
| **Accent (the only UI hue)** | `--acc` / `--acc-h` / `--acc-p` | `#5fd09b` / `#83e2b5` / `#3eaa76` |
| Accent tint / border / on-accent | `--acc-soft` / `--acc-border` / `--on-acc` | `rgba(95,208,155,.2)` / `rgba(95,208,155,.6)` / `#0a1710` |
| Logo glow | `--glow` | `#4bc8ae` |
| Status | `--danger` / `--success` / `--warn` | `#e06b5b` / `#68c490` / `#d8b45b` |

Text is **warm cream, never pure white**. Backgrounds are green-black, never
neutral grey. The accent is for the primary action, active states, eyebrows and
the highlighted half of a headline.

## Type

- **Display / UI labels:** a blocky pixel monospace face (the font file in `assets/fonts/`;
  the role is "chunky pixel mono", swappable via `--mono`). Headings are weight 700 with
  `letter-spacing: -.01em` (hero `-.03em`), line-height ~1.0-1.1. Labels,
  buttons, tags and eyebrows are this face too.
- **Body:** Inter 400 / 600 / 700 via `--sans`.
- **Eyebrow:** 12 px, 600, `letter-spacing: .14em`, uppercase, accent-light.
- **Headline pattern:** two lines, the second wrapped in `<span>` and coloured
  `--acc-h` ("Every version. / One click.").
- Scale: hero `clamp(44px, 7vw, 92px)`, h2 `clamp(30px, 4vw, 52px)`, lede
  `clamp(16px, 1.4vw, 19px)` in `#d9d4c4`, max 640 px wide.

## Shape and depth

- **Near-square corners:** 1 px on chips and buttons' inner details, 2 px on
  buttons and inputs, 3 px on cards and panels. Never pill-shaped.
- **Hard drop shadow, not blur:** cards `0 4px 0 rgba(0,0,0,.48)`; the primary
  button `0 3px 0 rgba(95,208,155,.55)`. On `:active` the button moves down 2 px
  and the shadow shrinks to 1 px, so it feels pressed.
- **Glass panels** (`--glass`) with a `1px solid var(--soft)` border sit over the
  3D layer; `backdrop-filter: blur(8-14px)` on ghost buttons and the solid header.
- **Background texture:** a 64 px tiled "slate" SVG (faint horizontal and vertical
  slashes) under the gradient `135deg #0e1110 → #151b17 → #090c0a`.

## Components

- **Buttons:** heights 38 / 48 / 56 / 64 (`sm / default / lg / xl`), mono 600.
  `primary` = accent fill + dark text; `ghost` = `rgba(29,36,32,.7)` + soft border.
  Disabled "coming soon" state: card colour + a tiny accent "SOON" tag.
- **Cards:** glass, 3 px radius, 4 px hard shadow; hover → border `--stroke` and
  `translate: 0 -2px`.
- **Chips:** mono 600 12 px on `--card` with a soft border; warn variant is gold
  text on a faint gold fill.
- **Switch:** 66×36 track, square 28 px knob, track turns accent when on, knob
  travels 30 px.
- **Header:** fixed, 68 px, transparent at the top, glass-solid after 40 px of
  scroll, hides on scroll down and returns on scroll up.
- **Brand lockup:** the mark (26 px, stroke 11, teal glow) + wordmark (84×17).
  Both are `<use>` of the SVG symbols in `index.html`, the launcher's own paths.

## Legibility over a bright sky

The golden-hour sky is bright, so text sits on **soft dark pools**: a radial
gradient pseudo-element per section (`rgba(12,10,18,.4-.6)` fading to 0, sized so
the edge never shows as a line between sections) plus a `text-shadow: 0 2px 14px
rgba(8,6,12,.55)`. Secondary text is lifted to `#d9d4c4` over the sky.

## Layout

- Gutter `clamp(16px, 4vw, 56px)`. Sections pad `clamp(96px, 12vw, 160px)`.
- Hero copy is left (max 620 px) so the island sits right; mobile flips it to
  bottom-aligned with the island above.
- Cards use `grid-template-columns: repeat(auto-fit, minmax(260px, 1fr))`.
- Breakpoint: 900 px. Below it the nav keeps only the Store link and hover-only
  hints disappear.
- Accessibility: skip link, `:focus-visible` 2 px accent outline, `aria-checked`
  on the switch, `aria-hidden` on the decorative 3D layers.

## Page checklist for a new page

Same `<head>` (theme-color `#0e1110`, `color-scheme: dark`), same `.backdrop`,
`site.css` first, header + footer copied from an existing page, ids unique
across the document, and the page readable with the canvas hidden.
