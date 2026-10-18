# Origin motion — the launch journey

One short spec for the launcher (WPF) and the mod (Java). Durations live in two
places only, and they mirror each other on purpose:

| Token | Value | Launcher (`Theme/Spacing.xaml`) | Mod (`StartupTransition`, 1.21.1) |
|---|---|---|---|
| Intro | 560 ms | `Motion.Intro`: mark assembles on first launcher open | `REVEAL_MS`: loading screen dissolves into the title |
| Handoff | 320 ms | `Motion.Handoff`: Play → scene, scene → game | `WORLD_MS`: first world frames rise out of charcoal |
| Scene | 180 ms | `Motion.Scene`: overlay fade in | — |

Easing: ease-out for everything that arrives (WPF `CubicEase EaseOut`; the mod's
`OriginTheme.easeOut` bezier), ease-in-out for the launcher scene leaving. Opacity,
scale and small (≤10 px) rises only. No particles, no spin-ups, no flashes, no
camera moves. The only continuous motion is the loading mark's slow spin and the
indeterminate bar, and both stop the moment the scene closes.

Reduced motion: Windows "Show animations" off zeroes every launcher token
(`App.DisableMotion`), so everything snaps and the startup intro is skipped.
Minecraft has no system-wide equivalent; the in-game transitions are short and
never delay input.

## States

```
Launcher:  Home ─Play→ Preparing (auth/Java/files, real counts) → Starting Minecraft
           → Loading Minecraft (window is up) ─handoff→ scene lowers, game in front
Game:      Origin loading screen ─resources done→ 560 ms dissolve → title (mark settles,
           buttons rise, clickable as the dissolve ends) ─world→ Origin loading scene
           → 320 ms fade out of charcoal into gameplay
```

## The launcher ↔ game handshake

- The launcher passes `-Dorigin.handoff=<random 32-hex token>` per launch.
- The mod prints `origin-handoff <token> hello|loading|ready` once each to the
  game's stdout, which the launcher already captures. No socket, pipe or file,
  so nothing outside the game process can reach it; the token stops chat or
  other mods from faking a stage.
- `hello`: pre-launch. `loading`: first Origin loading-screen frame, so the
  launcher scene lowers. `ready`: the startup loading screen is gone (title, or a Quick Play world
  starting to load), so Play is done.
- Fallbacks (`Core/Launch/GameHandoff.cs`): window seen with no hello for 3 s
  means an older build, so it behaves as before; no loading frame 20 s after the
  window means the scene lowers anyway; 90 s from process start means ready
  regardless. A crash or exit at any point lowers the scene and shows the error.
