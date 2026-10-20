# Recipe: keybinds, toggle sprint/sneak, zoom, freelook, mouse and scroll

## Rules that hold everywhere
- **Toggle Sprint**: one `SprintKeyMixin` on `KeyMapping#isDown` makes `keySprint` read as held. It works in every era.
  Don't force `setSprinting` from the tick loop.
  - Test with `/tp <name>`, not `@s`: the AltGr/@ scripting trap.
- Keys are stored as **GLFW codes** in `originclient-*.json` on every version, so Settings Sync works across versions.
  1.8.9 translates at the edge with `util.KeyCodes` (`glfwToLwjgl` / `lwjglToGlfw`; mouse = `-100 + button`).
- Register keys with Fabric `KeyBindingHelper` up to 1.21.11; `KeyMapping.getKey` is absent. 26.x uses `keymapping.v1.KeyMappingHelper.registerKeyMapping`.

## Per-era facts

| Era | Facts (proven unless marked) |
|---|---|
| G9 1.8.9 | LWJGL 2 `org.lwjgl.input.Keyboard` / `Mouse`; KeyBinding; InputEvent.KeyInputEvent on the FML bus |
| G2 1.20 | `mouseScrolled` is 3-arg on 1.20 / 1.20.1 (4-arg from 1.20.2) |
| G1–G4 | GLFW key codes; `Screen.keyPressed(int,int,int)` |
| G5 1.21.10 / 1.21.11 | 1.21.9+: `MouseButtonEvent` / `KeyEvent` / `CharacterEvent` records replace the raw ints; `KeyMapping.Category` is typed |
| G6 26.1 / 26.2 | as 1.21.10 + Fabric renames (`ClientCommandManager` → `ClientCommands`). Zoom: `Camera.calculateFov(F)` = world FOV (zoom hooks here), `calculateHudFov` = hand. Freelook: `Camera.alignWithEntity`'s setRotation |
| G6 26.3 | **SDL3, no GLFW**: `InputConstants.KEY_*` (KEY_RSHIFT, KEY_RETURN, ...), `MOUSE_BUTTON_LEFT`, `PRESS`; `InputConstants.Type.KEYSYM` → `KEYBOARD`; `InputConstants.isKeyDown(int)` (no window); KeyEvent has `key()/keycode()/modifiers()` (no `scancode()`). Raw mouse = SDL hint `SDL_MOUSE_RELATIVE_SYSTEM_SCALE`. Window via `SDLVideo.*`. Held left button = `mouseHandler.isLeftPressed()` |
| 1.21.2+ | `getFov` returns Float |

## Proof
- Right Shift opening the menu needs a real key event; in CI the dev-shots harness drives it.
- Tests from PowerShell on Will's PC need keybd_event, but never open windows there. Use CI.
- Toggle states: `set=world` plus the harness's input steps. Feel (latency, hold vs toggle) stays with Will.
