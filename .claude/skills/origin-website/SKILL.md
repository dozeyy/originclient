---
name: origin-website
description: The Origin website's core design concepts, with nothing game-specific in them — a floating island hanging in a golden-hour sky, a logo built from three orbiting rings with glowing orbs, small 3D objects on a turntable, a dark green-black + one-mint-accent style, and slow eased motion with a reduced-motion path. Load before building or changing anything on originclient.org (website/), or when making a new page, preview, mockup or launcher/promo art that should feel like it.
---

# Origin website: core concepts

The site is **an island in the sky**. A small floating island hangs in a
golden-hour sky. Origin's logo (three tilted rings) orbits it, so the island is the
nucleus of the mark. The page scrolls over it, and scrolling only moves the
camera. Type, buttons and cards come from the launcher so site, launcher and game
read as one product.

Brief: *professional + fun, 3D, real assets only.*

## The five ideas (hold all of them)

1. **A floating island that is lit, not just placed.** Seeded, hand-shaped
   terrain: grass top, tapering underside, a few readable props. Hard-edged faces
   with fixed per-face light and soft corner shadows, then a warm "sun + sky +
   orb light" grade. → `references/island.md`
2. **The logo is a physical object.** Three stroke-only rings at 0/60/120°,
   each an ellipse with a 0.355 ratio (a circle tilted ~69°), always turned to
   face the camera so they read as the logo. Each ring carries a glowing orb with a
   comet tail that lights the island. → `references/objects.md`
3. **Small 3D objects get a turntable.** Halo, crown, cape-style panels and
   particles are built from simple primitives and procedural art, shown on a
   neutral figure that slowly turns and can be dragged. → `references/objects.md`
4. **The launcher is the style guide.** Dark green-black panels, warm cream
   text, one mint accent, a blocky display face over a clean body face,
   near-square corners (1-3 px), hard 3-4 px drop shadows. → `references/style.md`
5. **Motion is eased, slow and never jumps.** One curve
   (`cubic-bezier(.16, 1, .3, 1)`), exponential easing per frame for anything
   3D, slow ambient loops (26/34/42 s ring turns), seamless loops for anything
   shown as a card, and a full reduced-motion path. → `references/motion.md`

Shaders (the warm lit look) are **on by default**. The plain, unlit look is the
comparison a visitor switches to, never the starting state.

## Where the real code is (the source of truth)

This skill sums the code up; the files decide.

| File | Owns |
|---|---|
| `website/js/world.js` | the island: generation, meshing, surface shader, rings + orbs, dust, camera poses + easing |
| `website/js/site.js` | WHAT is shown: scene list, scroll→pose blending, shaders switch, header, reveals, `DOWNLOAD.live` |
| `website/js/cosmetics.js` | the 3D objects: procedural art painters, halo/crown/panels, particles, turntable, card thumbnails |
| `website/css/site.css` | tokens, type, buttons, cards, sky backdrop, legibility pools, reveal |
| `website/css/store.css` | store-only pieces on top of site.css |
| `website/index.html` | page shape + the `#mark` / `#wordmark` SVG symbols (the launcher's own paths) |
| `tools/website/build_assets.py` | textures, fonts and three.js from their real sources (never hand-edit `website/assets/`) |

The split matters: **site.js says what, world.js says how.** A new scene is a
new entry in `SCENES` (site.js) plus a new pose in `POSES` (world.js), with no
camera code in site.js.

## Hard rules

- **Real assets only.** Textures and fonts come from `build_assets.py`. No stock
  art, no AI images, no dropped-in PNGs.
- **No product replicas on the site.** Will removed the scroll-through installer
  replica, the launcher Home replica and an in-game screen chapter ("takes too
  long" / "looks trash"). The site is the island, not mockups of the apps. Don't
  re-add without asking.
- **The island's top layer is all grass** (Will). Props sit on top of it.
- **Rings never cut through the island.** Ring radii come from the island's
  bounding sphere plus clearance, so growing the island grows the rings. Keep it
  that way.
- **Fail soft.** No WebGL → `body.no-webgl`, the flat CSS sky stays, the store
  shows flat art. The page must read fully without the 3D layer.
- **Monochrome + one accent.** Mint `#5fd09b` / `#83e2b5` is the only UI hue.
  Gold, red and purple appear only inside 3D items and the sky, never in UI chrome.

## Workflow for a change

1. Read the files you'll touch (table above) and the matching reference file.
2. Put new behaviour where the split says (what → site.js, how → world.js /
   cosmetics.js, look → CSS tokens). Never hard-code a colour that has a token.
3. Check it in a real browser. The sandbox has Chromium with SwiftShader WebGL:
   serve `website/` (`python3 -m http.server -d website 8000`) and screenshot with
   playwright-core using `executablePath: '/opt/pw-browsers/chromium'`, at
   **desktop 1440×900 and phone 390×844**, at every scene (scroll to each section,
   wait ~2 s for the camera to settle). Expect zero console errors. Also check
   with shaders off and with reduced motion emulated.
4. Gotchas already hit: a relative `url()` inside a CSS custom property resolves
   against the *stylesheet*, so set such URLs from JS as absolute; SVG `<symbol>`
   ids are global and can collide with section ids (`#play` did).
5. The website deploys from `main` via `.github/workflows/deploy-website.yml`,
   separate from launcher releases (no tag needed).

## Using the look elsewhere

- Mark → the `#mark` symbol in `website/index.html` (stroke-only, glow
  `drop-shadow(0 0 6px rgba(75,200,174,.45))`).
- Colours → the `:root` tokens in `site.css` (same values as the launcher's `Colors.xaml`).
- 3D items → `cosmetics.js` exports `createThumbnailer()` (stills + seamless loop
  frames). Reuse it; don't redraw items.
