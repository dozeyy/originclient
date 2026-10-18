# Origin motion — the launch journey

One short spec for the launcher (WPF) and the mod (Java). Will's brief: from
clicking Play to standing in a world, nothing may feel like a cut. The launcher
shows a plain progress screen while it prepares and starts the game; Minecraft
then opens its own window normally, and from its loading screen on the game
carries the Origin scene: it lives on behind the menus and steps aside once
the world is ready (it then appears like vanilla). Will 2026-10-18: no launcher-side
loading scene and no window glide — the game loads like normal, then animates.

| Token | Value | Launcher (`Theme/Spacing.xaml`, `UI/Controls`) | Mod (1.21.1) |
|---|---|---|---|
| Intro | 560 ms | `Motion.Intro`: the launcher mark assembling on first open | `StartupTransition.REVEAL_MS`: loading screen dissolves into the title |
| Settle | 320 ms | `Motion.Settle`: wordmark and Play card settling in under the first-open mark | — |
| Scene | 180 ms | `Motion.Scene`: progress screen lines arriving | — |
| Morph | 480 + 720 ms | — | `MarkMotion.HOLD_MS`/`MORPH_MS`: each shape of the loading mark's loop holds, then morphs |
| Flight | 950 ms | — | `MarkMotion.FLIGHT_MS`: the loading mark flies onto the title's logo spot and becomes the logo |

Easing: cubic ease-out for arrivals, cubic in-out for travel (the glide, the
assemble's flight) and leaving. Spins are allowed only as the rings' own orbit
(wall-clock angles, so the launcher's and the game's rings line up exactly), the
assemble's spin-up that settles into it, and the loading mark's turn with each
morph. No particles, no flashes.

Smoothness (Will 2026-10-18): in the game every loading-scene motion runs on
`RingField.sceneMs()`, a clock that steps at most one 30 fps frame at a time and
quietly earns the rest back, so a slow frame while Minecraft loads is a pause,
never a skip. Bar ends are placed to a fraction of a pixel (`fillSmooth`, launcher
`SlideFill`). Menus are snappy: fades of 110 ms or less that never block a click,
closing is instant, and the main menu's entrance plays once per game start.

Reduced motion: Windows "Show animations" off zeroes every launcher token
(`App.DisableMotion`): screens appear complete, the mark stays still.
Minecraft has no system-wide equivalent; the in-game transitions are short and
never delay input.

## The journey

```
Launcher:  Play press → the progress screen: spinning Origin mark, version, stage
           line, a bar that fills to real counts (sliding block when a stage has
           none), Cancel. Preparing → Starting Minecraft → Loading Minecraft.
Game:      opens its own window normally (visible, Minecraft's default place and
           size); once it shows the Origin loading screen the launcher's screen
           lowers. loading screen: the mark's three strokes bloom from a point
           and morph on a loop (circles → triangles → square and diamond → the
           mark), the wordmark builds in letter by letter, the bar fills
           ─resources done→ 560 ms dissolve → title: the mark itself flies onto
           the logo spot, morphing into the logo as it goes (never a crossfade
           to a second logo); no panorama, the rings slow, drift and lean toward
           the mouse ─world→ the rings swing back into the loading orbit
           with real chunk progress → the world appears the frame it is ready, like
           vanilla (Will 2026-10-18: no zoom/lens).
```

## The launcher ↔ game handshake

- The launcher passes `-Dorigin.handoff=<random 32-hex token>` per launch. It no
  longer passes `-Dorigin.handoff.window` (the hidden-window glide is gone).
- The mod prints `origin-handoff <token> hello|loading|ready` to the game's stdout,
  which the launcher already captures. No socket, pipe or file, so nothing outside
  the game process can reach it; the token stops chat or other mods from faking a
  stage. A `window x,y,w,h,scale` line from a mod build that still sends one is
  read as a hello and otherwise ignored.
- `hello`: pre-launch. `loading`: the game window is up on an Origin loading frame,
  so the launcher's progress screen lowers. `ready`: the startup loading screen is
  gone (title, or a Quick Play world loading).
- Fallbacks (`Core/Launch/GameHandoff.cs`): window seen with no hello for 3 s
  means an older build, so the window is the signal; no loading frame 20 s after
  the window means the screen lowers anyway; 90 s from process start means ready
  regardless. A crash or exit at any point lowers the screen and shows the error.
