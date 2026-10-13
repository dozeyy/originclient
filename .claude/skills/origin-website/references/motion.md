# Motion

The feel: **slow, soft landings, nothing snaps.** The island barely drifts, the
camera glides between scenes, UI settles with one shared curve.

## Two easing systems

**CSS / UI:** one curve everywhere, `--ease: cubic-bezier(.16, 1, .3, 1)` (fast
start, long soft landing). Durations: hover/press `.12s`, switch `.18s`, header
`.35s`, sky crossfade `.9s`, reveal `.7s`.

**3D / per-frame:** frame-rate independent exponential smoothing, never a fixed
step: `k = 1 - Math.exp(-dt * rate)`, then `value += (target - value) * k`.

| What | rate |
|---|---|
| Camera position, target, fov, screen shift | 4.2 |
| Pointer parallax | 3 |
| Shaders mix (lit ↔ plain) | 2.5 |
| Figure turn on release | `min(1, dt * 5)` |
| Figure camera framing | `min(1, dt * 4)` |

`dt` is clamped to 0.05 s so a stalled tab doesn't fling the camera.

## The camera is the choreography

Scrolling does not animate objects, it **moves the camera between named poses**:

```js
// site.js: each scene = [section id, pose name, darken amount]
SCENES = [['top','hero',0], ['versions','versions',0.62], ['shaders','shaders',0], ['get','get',0.2]]
```
Each frame, find the scene scrolling up into view, take
`t = smoothstep(1 - sectionTop / viewportHeight)`, and call
`world.setPose(from, to, t)`. world.js blends position, target, fov and a screen
shift (`setViewOffset`, so the island sits beside the text instead of behind it),
then eases `cur → blended` with the 4.2 rate above. Poses live in `POSES` in
world.js (`pos`, `target`, `fov`, `shiftX`, `shiftY`, `orbit`). Phones get a
separate hero pose (island above the headline, no sideways shift).

Add a scene = one `SCENES` row + one `POSES` entry.

## Ambient motion (always on, always slow)

- **Camera drift:** `orbitAngle += dt * 0.045 * orbit`, a full lap takes minutes.
  Pointer adds ±0.12 rad of yaw and ±1.5 of height, smoothed.
- **Rings:** turn at 26 / 34 / 42 s per revolution (middle one reversed); orbs lap
  their rings at 0.4× that period; the glow breathes with the shaders mix.
- **Dust:** rises 0.35 units/s and wraps.
- **Water:** UV wobble + slow scroll in the shader (`uTime`).
- **Hints:** scroll cue arrow bobs on a 1.8 s loop; "Scroll" label pulses to 35%
  over 2.4 s.

## Reveal on scroll

Section heads and cards start at `opacity: 0; translate: 0 24px` and become
`.is-in` (opacity 1, translate 0) via an `IntersectionObserver` at `threshold:
.15`, then are unobserved so they never re-hide. Use the individual `translate`
property so it doesn't fight `transform` on the same element.

## Seamless loops (anything shown as a card)

A shop card plays a pre-rendered frame loop, so any animated item **must repeat
exactly**:

- One global `LOOP = 2.4 s`; the Orbit Halo uses `3 × LOOP`.
- Every angle/phase is `whole_number × (2π / loop) × t`: ring precession `1, -2,
  3` turns, wobble `2, 3, 1`, beads `4, -5, 6` per loop, pulse `4`.
- Particle age = `fract(phase * (1 + i % 2) + hash(i))`, so each repeats in 1 or 2 loops.
- Randomness is seeded (`rng(seed)`, `hash(i)`), never `Math.random()`.
- Unit test by eye: render frame 0 and frame N; they must be identical.

## Interaction motion

- Drag the turntable: pointer capture, `0.012 rad/px`; auto-spin pauses while
  dragging, resumes ~2.5 s after release; picking a new item holds ~4.5 s on its
  best angle.
- Pause when not seen: render loops stop when the canvas is off-screen
  (`IntersectionObserver`) or the tab is hidden, and restart on return.
- Button press: `translateY(2px)` over `.12s` with the shadow shrinking.

## Reduced motion (not optional)

`prefers-reduced-motion: reduce` is honoured in both systems:
- CSS: animation and transition durations collapse to `.001s`, smooth scroll off.
- 3D: easing rate jumps to 30 (poses effectively cut), camera orbit and ring
  spin stop, the figure faces its best angle and shows the item's `still` frame
  (`STILL_T = 0.15 × LOOP`) instead of animating.

Anything new must have a defined resting frame for this path.
