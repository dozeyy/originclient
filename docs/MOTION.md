# Origin motion — the launch journey

One short spec for the launcher (WPF) and the mod (Java). Will's brief: from
clicking Play to standing in a world, nothing may feel like a cut. One picture —
the Origin loading scene's orbiting rings — carries the whole way: it unfolds
out of the Play button, becomes the game's loading screen, lives on behind the
menus, and steps aside once the world is ready (it then appears like vanilla).

| Token | Value | Launcher (`Theme/Spacing.xaml`, `UI/Controls`) | Mod (1.21.1) |
|---|---|---|---|
| Intro | 560 ms | `Motion.Intro`: launcher mark on first open; the scene's glide onto the game window | `StartupTransition.REVEAL_MS`: loading screen dissolves into the title |
| Handoff | 320 ms | `Motion.Handoff`: scene copy melts away once the game shows the same picture | `OriginHandoff.RAMP_MS`: game window fades in |
| Scene | 180 ms | `Motion.Scene`: launcher lines leave as the glide starts | — |
| Assemble | 1100 ms | `LoadingScene.AssembleMs`: Play → rings spin out into the orbit | ring-field settle (`RingField`) |
| Glide wait | 620 ms | — | `OriginHandoff.GLIDE_MS`: window stays invisible while the scene glides on |

Easing: cubic ease-out for arrivals, cubic in-out for travel (the glide, the
assemble's flight) and leaving. Spins are allowed only as the rings' own orbit
(wall-clock angles, so the launcher's and the game's rings line up exactly) and
the assemble's spin-up that settles into it. No particles, no flashes.

Smoothness (Will 2026-10-18): in the game every loading-scene motion runs on
`RingField.sceneMs()`, a clock that steps at most one 30 fps frame at a time and
quietly earns the rest back, so a slow frame while Minecraft loads is a pause,
never a skip. Bar ends are placed to a fraction of a pixel (`fillSmooth`, launcher
`SlideFill`). Menus are snappy: fades of 110 ms or less that never block a click,
closing is instant, and the main menu's entrance plays once per game start.

Reduced motion: Windows "Show animations" off zeroes every launcher token
(`App.DisableMotion`): the scene appears complete, no glide, no button fold.
Minecraft has no system-wide equivalent; the in-game transitions are short and
never delay input.

## The journey

```
Launcher:  Play press → the button folds into the Origin mark; its 3 rings unfold,
           spin up and fly out into the loading orbit (LoadingScene = the game's
           loading screen, laid out by the game's own formulas) while the charcoal
           rises behind them. Preparing (real counts on the bar) → Starting Minecraft
Handoff:   the game opens its window INVISIBLY, centred on the scene, and reports
           its rect → the launcher lifts the scene into a click-through window
           (SceneHandoffWindow) that glides and stretches onto that rect →
           the game window fades in showing the identical picture → the copy melts.
Game:      loading screen ─resources done→ 560 ms dissolve → title: no panorama,
           the same rings slow, drift and lean toward the mouse, the logo settles
           out of them ─world→ the rings swing back into the loading orbit with
           real chunk progress → the world appears the frame it is ready, like
           vanilla (Will 2026-10-18: no zoom/lens).
```

## The launcher ↔ game handshake

- The launcher passes `-Dorigin.handoff=<random 32-hex token>` per launch, and
  `-Dorigin.handoff.window=<cx>,<cy>` (the scene's centre, screen px).
- The mod prints `origin-handoff <token> hello|window x,y,w,h,scale|loading|ready`
  to the game's stdout, which the launcher already captures. No socket, pipe or
  file, so nothing outside the game process can reach it; the token stops chat or
  other mods from faking a stage.
- `hello`: pre-launch. `window`: the hidden window's rect and GUI scale (only with
  a window target, never fullscreen). `loading`: the game window is fully shown
  on an Origin loading frame, so the launcher's copy melts away. `ready`: the
  startup loading screen is gone (title, or a Quick Play world loading).
- Fallbacks (`Core/Launch/GameHandoff.cs`): window seen with no hello for 3 s
  means an older build, so it behaves as before; no loading frame 20 s after the
  window means the scene lowers anyway; 90 s from process start means ready
  regardless. In the game, the hidden window always shows within 3 s of its first
  frame even if no Origin loading frame draws. A crash or exit at any point lowers the scene and
  shows the error.
- Mixed-DPI monitor setups may land the glide a few pixels off; the crossfade
  hides it.
