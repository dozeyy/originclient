package com.origin.client.client.hooks;

import com.origin.client.OriginClient;
import com.origin.client.client.cosmetics.CosmeticEffects;
import com.origin.client.client.cosmetics.CosmeticLayer;
import com.origin.client.client.cosmetics.CosmeticsService;
import com.origin.client.client.social.Social;
import com.origin.client.client.social.SocialToast;
import com.origin.client.client.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

/**
 * Every Forge event the social area (friends, party, cosmetics) listens to.
 * Registered on both buses by OriginHooks; each handler is fail-soft (a broken
 * feature = vanilla behaviour, never a crash).
 *
 * 1.8.9 stand-ins for 1.16.5's Fabric callbacks:
 *  - END_CLIENT_TICK -> ClientTickEvent END: presence, the ping key, the social
 *    events turned into toasts / chat lines, the equipped menu theme
 *    (ThemeManager.tick); on the first tick the cosmetics layer joins the player
 *    renderers (CosmeticLayer.installAll);
 *  - ScreenMouseEvents.allowMouseClick -> GuiScreenEvent.MouseInputEvent.Pre:
 *    a left press on a Friends toast is taken (cancelled) before the screen sees
 *    it, in any screen including the chat box;
 *  - WorldRenderEvents.LAST -> RenderWorldLastEvent: ping beams + the camera the
 *    party markers project through, and the cosmetics' sparkles (CosmeticEffects);
 *  - the toast stack itself (1.8.9 has no toast system) draws at the END of
 *    every render tick, over the HUD and any screen, as vanilla's toasts do.
 * Starting the social and cosmetics workers is idempotent, so they start here
 * too in case the client init doesn't (whichever runs first wins).
 */
public final class SocialHooks {
	private boolean layersInstalled = false;

	public SocialHooks() {
		try {
			CosmeticsService.start();
			Social.init();
		} catch (Throwable t) {
			OriginClient.LOGGER.warn("Origin social: start failed; friends and cosmetics stay off", t);
		}
	}

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent e) {
		if (e.phase != TickEvent.Phase.END) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getMinecraft();
			if (!layersInstalled) {
				layersInstalled = true;
				CosmeticLayer.installAll();
			}
			ThemeManager.tick();   // fail-soft inside
			Social.tick(mc);
		} catch (Throwable t) {
			// a bad tick drops this tick's presence/toasts, never the game
		}
	}

	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent e) {
		if (e.phase == TickEvent.Phase.START) {
			Social.newFrame();
			return;
		}
		try {
			Minecraft mc = Minecraft.getMinecraft();
			// F1 hides the in-game HUD; toasts follow it unless a menu is open
			if (mc.gameSettings.hideGUI && mc.currentScreen == null) {
				return;
			}
			SocialToast.renderAll();
		} catch (Throwable t) {
			// no toasts this frame
		}
	}

	@SubscribeEvent
	public void onScreenMouse(GuiScreenEvent.MouseInputEvent.Pre e) {
		try {
			if (Mouse.getEventButton() != 0 || !Mouse.getEventButtonState()) {
				return;
			}
			Minecraft mc = Minecraft.getMinecraft();
			GuiScreen screen = mc.currentScreen;
			if (screen == null) {
				return;
			}
			double mx = Mouse.getEventX() * (double) screen.width / Math.max(1, mc.displayWidth);
			double my = screen.height - Mouse.getEventY() * (double) screen.height / Math.max(1, mc.displayHeight) - 1;
			if (SocialToast.click(mx, my)) {
				e.setCanceled(true);   // 1.8.9 NAME UNVERIFIED: MouseInputEvent.Pre is @Cancelable
			}
		} catch (Throwable t) {
			// the click goes to the screen as usual
		}
	}

	@SubscribeEvent
	public void onRenderWorldLast(RenderWorldLastEvent e) {
		Social.renderWorld(e.partialTicks);   // fail-soft inside
		CosmeticEffects.renderWorld(e.partialTicks);   // fail-soft inside
	}
}
