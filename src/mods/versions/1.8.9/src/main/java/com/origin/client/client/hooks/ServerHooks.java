package com.origin.client.client.hooks;

import com.origin.client.client.gui.FpsCoachToast;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.mods.AutoGG;
import com.origin.client.client.mods.AutoTextHotkey;
import com.origin.client.client.mods.ChatEvents;
import com.origin.client.client.mods.FpsCoach;
import com.origin.client.client.mods.HypixelBedwars;
import com.origin.client.client.mods.NickHider;
import com.origin.client.client.mods.Quickplay;
import com.origin.client.client.mods.ScreenshotChat;
import com.origin.client.client.mods.ServerMods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The server area's Forge hooks (chat, Hypixel, Bed Wars, Team View, Discord,
 * FPS Coach, Nick Hider, Quickplay, Auto Text, Screenshot) -- 1.8.9's stand-in
 * for the Fabric callbacks ServerMods / FpsCoach / ScreenshotChat registered on
 * 1.16.5, and for OriginClientMod's "Lunar batch 3a" tick (the 1.8.9 hub ticks
 * no other area, so every server-area tick starts here). OriginHooks registers
 * this on both buses; each handler only fires on the bus that posts its event.
 *
 * Every handler is fail-soft per feature: one mod throwing leaves that mod
 * quiet for the tick/frame/line, never the rest, never the game.
 *
 * Mixins that remain for what no event covers (mixin/server): chat timestamps /
 * stack / unlimited / smooth / shadow (GuiNewChat), Nick Hider text (FontRenderer),
 * skins (NetworkPlayerInfo) and lobby ID (ScorePlayerTeam.formatPlayerName),
 * the screenshot chat line (ScreenShotHelper) and the Bed Wars trap title
 * (GuiIngame.displayTitle).
 */
public class ServerHooks {
	public ServerHooks() {
		try {
			OriginHooks.registerObject(new ChatClicks());
		} catch (Throwable t) {
			// screenshot buttons fall back to their click events
		}
	}

	// ---- client tick (FML bus) ----

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null) {
			return;
		}
		// 1.16.5's tickLunarBatch3a: before any no-player return, since Nick
		// Hider and Screenshot work on menus too.
		try {
			AutoTextHotkey.tick(mc);
		} catch (Throwable t) {
			// hotkeys just don't fire this tick
		}
		try {
			AutoGG.tick(mc);
		} catch (Throwable t) {
			// a pending gg is dropped at worst
		}
		try {
			NickHider.tick(mc);
		} catch (Throwable t) {
			// names keep their last mapping
		}
		try {
			Quickplay.tick(mc);
		} catch (Throwable t) {
			// keys just don't fire this tick
		}
		try {
			ScreenshotChat.tick(mc);
		} catch (Throwable t) {
			// key just doesn't fire this tick
		}
		// Hypixel -> Discord -> Bed Wars (order matters; each fail-soft inside).
		ServerMods.tick(mc);
		try {
			FpsCoach.tick(mc);
		} catch (Throwable t) {
			// the coach just skips this tick
		}
	}

	// ---- every frame (FML bus) ----

	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		if (event.phase == TickEvent.Phase.START) {
			try {
				HypixelBedwars.endHearts();   // safety: never carry the hardcore flag into a new frame
			} catch (Throwable t) {
				// fail-soft
			}
			return;
		}
		try {
			FpsCoach.frame();
		} catch (Throwable t) {
			// a lost frame sample is harmless
		}
		try {
			FpsCoachToast.render();   // after the HUD and any screen: on top, like a toast
		} catch (Throwable t) {
			// fail-soft
		}
	}

	// ---- incoming chat (Forge bus) ----

	// ClientChatReceivedEvent: type 0 chat, 1 system, 2 action bar. `message` is
	// writable (the rewrite) and cancelling drops the line (the hide).
	@SubscribeEvent
	public void onChat(ClientChatReceivedEvent event) {
		try {
			IChatComponent message = event.message;
			if (message == null) {
				return;
			}
			boolean overlay = event.type == 2;
			IChatComponent shown = ChatEvents.modify(message, overlay);
			if (!ChatEvents.allow(shown, overlay)) {
				event.setCanceled(true);
				return;
			}
			ChatEvents.received(shown, overlay);
			if (shown != message) {
				event.message = shown;
			}
		} catch (Throwable t) {
			// deliver the line untouched
		}
	}

	// ---- sounds (Forge bus) ----

	// PlaySoundEvent fires inside SoundManager.playSound for every sound (server
	// sound packets included); result = null means it never starts.
	@SubscribeEvent
	public void onSound(PlaySoundEvent event) {
		try {
			if (HypixelBedwars.muteSound(event.sound)) {
				event.result = null;
			}
		} catch (Throwable t) {
			// never break audio over an alert
		}
	}

	// ---- HUD (Forge bus) ----

	// Bed Wars' vanilla-HUD half, at LOWEST priority so a handler that cancels
	// the element first (another HUD mod) wins and we never raise the hearts
	// flag for a draw that won't happen.
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
		try {
			switch (event.type) {
				case ARMOR:
					if (HypixelBedwars.hideArmor()) {
						event.setCanceled(true);
					}
					break;
				case FOOD:
					// also skips anything drawn in FOOD's Post (the Saturation overlay)
					if (HypixelBedwars.hideFood()) {
						event.setCanceled(true);
					}
					break;
				case HEALTH:
					HypixelBedwars.beginHearts(Minecraft.getMinecraft());
					break;
				default:
					break;
			}
		} catch (Throwable t) {
			// leave the element to vanilla
		}
	}

	@SubscribeEvent
	public void onOverlayPost(RenderGameOverlayEvent.Post event) {
		try {
			if (event.type == RenderGameOverlayEvent.ElementType.HEALTH) {
				HypixelBedwars.endHearts();
			} else if (event.type == RenderGameOverlayEvent.ElementType.ALL) {
				HypixelBedwars.endHearts();
				ServerMods.hudPass(new Gfx());   // Team View markers
			}
		} catch (Throwable t) {
			// fail-soft
		}
	}

	// ---- world pass (Forge bus) ----

	@SubscribeEvent
	public void onWorldLast(RenderWorldLastEvent event) {
		ServerMods.worldPass(event.partialTicks);   // Team View projection (fail-soft inside)
	}

	// ---- chat screen clicks (Forge bus) ----

	/**
	 * Screenshot chat buttons: our button runs before GuiChat's own click
	 * handling (which would otherwise just open the file). Its own handler object,
	 * registered from the constructor: GuiScreenEvent.MouseInputEvent.Pre is a
	 * nested event class this module's name lookups didn't list, and if it were
	 * ever missing only this one hook would fail to register -- not every server
	 * hook above. Pre is cancelable in Forge 1.8.9; guarded anyway so a
	 * non-cancelable build only costs the fallback (vanilla opens the file).
	 */
	public static class ChatClicks {
		public ChatClicks() {
		}

		@SubscribeEvent
		public void onMouseInput(GuiScreenEvent.MouseInputEvent.Pre event) {
			try {
				if (event.gui instanceof GuiChat && ScreenshotChat.onChatClick(Minecraft.getMinecraft())
						&& event.isCancelable()) {
					event.setCanceled(true);
				}
			} catch (Throwable t) {
				// vanilla handles the click
			}
		}
	}
}
