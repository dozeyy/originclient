package com.origin.client.client.mods;

import com.origin.client.client.gui.Gfx;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Wires the server-integration mods (Lunar parity batch 3b: Team View, Discord
 * presence (always on), Hypixel, Hypixel Bed Wars) into Fabric's events, from one call in
 * OriginClientMod.onInitializeClient. Every listener is wrapped so a failure in
 * one of these mods can never break a tick, a frame or an incoming chat line --
 * it just leaves that mod quiet.
 *
 * 1.17.1: Fabric API 0.46.1 has no ClientReceiveMessageEvents (1.19+), so the
 * chat side (Hypixel's hide/rewrite, Bed Wars' trap lines) runs from
 * ChatReceiveMixin -> ChatEvents instead. HudRenderCallback hands a PoseStack
 * here (GuiGraphics is 1.20+); it is wrapped in the module's Gfx shim.
 */
public final class ServerMods {
	private ServerMods() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
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
		});

		// Team View: project heads in the world pass, draw markers in the HUD pass.
		WorldRenderEvents.LAST.register(ctx -> {
			try {
				TeamView.capture(ctx);
			} catch (Throwable t) {
				// fail-soft
			}
		});
		HudRenderCallback.EVENT.register((poseStack, delta) -> {
			try {
				TeamView.render(new Gfx(poseStack));
			} catch (Throwable t) {
				// fail-soft
			}
		});

		registerLunarBedwars();
	}

	// Hypixel Bed Wars (Lunar parity): its own tick, registered after the one
	// above so Hypixel.tick has already refreshed onHypixel / inBedwars when
	// HypixelBedwars.tick reads them. Its chat lines arrive via ChatEvents.
	private static void registerLunarBedwars() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			try {
				HypixelBedwars.tick(mc);
			} catch (Throwable t) {
				// fail-soft
			}
		});
	}
}
