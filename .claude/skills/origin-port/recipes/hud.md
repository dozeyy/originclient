# Recipe: HUD elements, chat, tab list, scoreboard, HUD editor

## Per-era hooks and APIs

| Era | Facts (proven unless marked) |
|---|---|
| G9 1.8.9 | RenderGameOverlayEvent.Pre/Post per ElementType first; GuiIngame/GuiIngameForge, GuiNewChat, GuiPlayerTabOverlay, static BossStatus. Fields via `@Accessor` in the area's mixin package. No cooldowns, offhand, toasts, or boss list (see PORT-189.md "Things that don't exist") |
| G8/G7 pre-1.19.3 | `mc.getFps()` missing on ≤1.19.2: use the MinecraftFpsAccessor static. `PlayerTabOverlay.getPlayerInfos()` does NOT exist before 1.19.3 (reasoned): rebuild vanilla order yourself (OriginTabList.vanillaPlayerList). Tab-suppress mixin cancels vanilla's list only when `this instanceof PlayerTabOverlayAccessor`, so a failed accessor leaves vanilla's list. ≤1.18.2: tab heads blit face (8,8) + hat (40,8) by hand. No OptionInstance below 1.19 (chatScale, chatOpacity, textBackgroundOpacity are plain fields) |
| G7 1.19.3 / 1.19.4 | getPlayerInfos exists (1.19.3 relies on it: reasoned). `mc.getFps()` exists from 1.19.3 |
| G2 1.20 | hearts and XP from `textures/gui/icons.png`: heart container (16,0), full (52,0), half (61,0) 9x9; XP bar background (0,64) 182x5. Scoreboard pre-ScoreHolder |
| G1 1.20.2 / 1.20.4 | sprite atlas for hearts and XP. ScoreHolder scoreboard API from 1.20.3 |
| G1 1.21 / 1.21.1 | source. Chat trim options split at 1.21 (dead-option audit) |
| G4 1.21.6 / 1.21.8 | deferred GUI. Chat shadow is a lambda on 1.21.6+. Bake AA masks: per-pixel AA panels cost about 130 draws each |
| G5 1.21.11 | no chat-shadow flag. HUD drawn on top at RETURN, order 2000 |
| G6 26.1 | `Gui` IS the HUD with extract* names: render→extractRenderState(GGE, DeltaTracker), renderHotbarAndDecorations→extractHotbarAndDecorations, renderCrosshair/Effects/Food/Title/Armor/Heart→extract*. `mc.gui.getChat()`, `mc.options.hideGui` |
| G6 26.2 / 26.3 | HUD is `net.minecraft.client.gui.Hud` = `mc.gui.hud` (getChat, getTabList, getBossOverlay, `isHidden()` replaces options.hideGui). HUD mixins move Gui→Hud, same extract names; 1.21.11's RETURN/order-2000 hook carries over. 26.2 has no ChatComponent.render: use `ChatComponent.extractRenderState(GGE, Font, I, I, I, DisplayMode, Z)`; addMessage is private, so use addClientSystemMessage. `displayClientMessage(c, bool)` → `sendOverlayMessage` / `sendSystemMessage`. Toasts `mc.gui.toastManager()` |

## Traps
- A per-frame counter tied to frames, not time, breaks at high FPS. Advance in tick() and interpolate in render.
- A full-list scan every frame in a grid screen (Item Size) cost real FPS on deferred GUI. Cache it, and rebuild on change.
- Item stacks can't be built before a world loads on 26.x. `gui/ItemStacks.of()` returns EMPTY, so HUD-editor previews are blank on the title screen.

## Proof
- Use `dev-shots set=world` (HUD in a world) and `set=design` (HUD editor).
- Run the dead-option audit (`modmenu-options-config.md`) on every option the HUD card exposes.
