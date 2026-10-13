package com.origin.client.client.mods;

import com.origin.client.client.gui.Gfx;
import net.minecraft.client.Minecraft;

/**
 * The per-tick / per-frame entry points of the server-integration mods (Lunar
 * parity batch 3b: Team View, Discord Status, Hypixel, Hypixel Bed Wars). Every
 * call is wrapped so a failure in one of these mods can never break a tick, a
 * frame or an incoming chat line -- it just leaves that mod quiet.
 *
 * 1.8.9: there are no Fabric callbacks to register into. Forge events reach
 * hooks/ServerHooks (registered on both buses by the foundation), which calls
 * {@link #tick}, {@link #worldPass} and {@link #hudPass} below; incoming chat
 * goes ServerHooks -> ChatEvents. {@link #register()} stays (OriginClientMod
 * calls it, as on every version) and has nothing left to do.
 */
public final class ServerMods {
	private ServerMods() {
	}

	/** Kept for OriginClientMod's call; Forge events arrive through ServerHooks. */
	public static void register() {
	}

	/** End of every client tick. Hypixel first, so Hypixel.tick has refreshed
	 *  onHypixel / inBedwars when HypixelBedwars.tick reads them. */
	public static void tick(Minecraft mc) {
		try {
			Hypixel.tick(mc);
		} catch (Throwable t) {
			// fail-soft
		}
		try {
			DiscordPresence.tick(mc);
		} catch (Throwable t) {
			// fail-soft
		}
		try {
			HypixelBedwars.tick(mc);
		} catch (Throwable t) {
			// fail-soft
		}
	}

	/** Team View: project heads in the world pass (RenderWorldLastEvent). */
	public static void worldPass(float partialTicks) {
		try {
			TeamView.capture(partialTicks);
		} catch (Throwable t) {
			// fail-soft
		}
	}

	/** Team View: draw the markers in the HUD pass (RenderGameOverlayEvent.Post ALL). */
	public static void hudPass(Gfx g) {
		try {
			TeamView.render(g);
		} catch (Throwable t) {
			// fail-soft
		}
	}
}
