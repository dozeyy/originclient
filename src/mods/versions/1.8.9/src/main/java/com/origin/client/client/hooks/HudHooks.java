package com.origin.client.client.hooks;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.hud.BossBarHud;
import com.origin.client.client.hud.CombatTracker;
import com.origin.client.client.hud.HudElements;
import com.origin.client.client.hud.OriginTabList;
import com.origin.client.client.minimap.MinimapHud;
import com.origin.client.client.mixin.hud.GuiPlayerTabOverlayAccessor;
import com.origin.client.client.mods.ClickStats;
import com.origin.client.client.mods.CombatStats;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.mods.PingSampler;
import com.origin.client.client.mods.PortalCalc;
import com.origin.client.client.mods.SaturationOverlay;
import com.origin.client.client.mods.Stopwatch;
import com.origin.client.client.render.OriginCrosshair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.GuiIngameForge;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

/**
 * The HUD area's Forge hooks on 1.8.9 (registered on both buses by OriginHooks).
 * Every handler is fail-soft: an Origin HUD failure costs that frame's piece of
 * HUD, never the vanilla HUD or the game (mandate 4).
 *
 * What 1.16.5 did with mixins into Gui, and where it lives here:
 *  - Origin's HUD as the TOP-MOST layer (GuiHudMixin, Gui.render RETURN, order 2000)
 *    -> RenderGameOverlayEvent.Post(ALL) at LOWEST priority: GuiIngameForge posts
 *    it after the whole HUD, chat and player list, and LOWEST runs after every other
 *    mod's Post(ALL) overlay, so Origin lands on top. The Tab Editor's own list is
 *    drawn here too (above Origin's elements, as 1.16.5 does).
 *  - Crosshair (GuiCrosshairMixin) -> Pre(CROSSHAIRS), cancelled while drawn.
 *  - Boss Bar (BossBarMixin) -> Pre(BOSSHEALTH), cancelled while drawn.
 *  - Tab list suppression (PlayerTabOverlayMixin) -> Pre(PLAYER_LIST), cancelled
 *    while GuiPlayerTabOverlayAccessor applied (vanilla's list stays otherwise).
 *  - Saturation overlay (GuiFoodMixin) -> Post(FOOD), at Forge's food-row height.
 *  - Waypoint Locator Bar (GuiHudMixin's XP-bar / hotbar TAILs) -> Post(EXPERIENCE)
 *    in survival/adventure, Post(HOTBAR) when there's no XP bar.
 *  - Potion "Show In Inventory" (a Fabric ScreenEvents.afterRender) ->
 *    GuiScreenEvent.DrawScreenEvent.Post over any GuiContainer.
 *  - CPS / Keystrokes clicks (MouseHandlerMixin.onPress) -> MouseEvent (no screen)
 *    + GuiScreenEvent.MouseInputEvent.Pre (a screen owns the mouse).
 *  - Combat highlight / Combo / Reach (MultiPlayerGameModeMixin.attack) ->
 *    AttackEntityEvent for the local player.
 *  - Food tooltip (Fabric ItemTooltipCallback) -> ItemTooltipEvent.
 *  - The readouts' per-tick state -- Stopwatch keys, Ping samples, Combo/Reach
 *    confirmation, Minimap keys + cave mode (ticked by 1.16.5's OriginClientMod) and
 *    Portal Calculator (its own Fabric ClientTickEvents) -> ClientTickEvent END here:
 *    the 1.8.9 hub leaves every area to tick its own features.
 * Scoreboard and Titles have no Forge event: mixin/hud (GuiIngameScoreboardMixin,
 * GuiIngameForgeTitlesMixin). Color Saturation's grade at the HUD's head is the
 * world area's (WorldHooks, Pre(ALL)).
 */
public final class HudHooks {

	public HudHooks() {
	}

	private static Minecraft mc() {
		return Minecraft.getMinecraft();
	}

	// ---- frame timing ----

	/** The frame's partial tick for HUD pieces that interpolate (the minimap). */
	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent e) {
		if (e.phase == TickEvent.Phase.START) {
			MinimapHud.partial = e.renderTickTime;
		}
	}

	// ---- HUD layers ----

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onOverlayPre(RenderGameOverlayEvent.Pre e) {
		try {
			switch (e.type) {
				case CROSSHAIRS:
					if (Mods.on("crosshair") && OriginCrosshair.render(new Gfx())) {
						e.setCanceled(true);
					}
					break;
				case BOSSHEALTH:
					if (Mods.on("bossbar")) {
						BossBarHud.render(new Gfx());
						e.setCanceled(true);
					}
					break;
				case PLAYER_LIST:
					// Origin always owns the player-list draw (see onOverlayPostAll):
					// suppress vanilla's so it never doubles up -- but only while the
					// header/footer accessor applied, so a failed mixin = vanilla's list.
					if (mc().ingameGUI.getTabList() instanceof GuiPlayerTabOverlayAccessor) {
						e.setCanceled(true);
					}
					break;
				default:
					break;
			}
		} catch (Throwable t) {
			// that layer is vanilla's this frame
		}
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onOverlayPost(RenderGameOverlayEvent.Post e) {
		switch (e.type) {
			case ALL:
				onOverlayPostAll();
				break;
			case FOOD:
				saturation(e);
				break;
			case EXPERIENCE:
				if (mc().playerController != null && mc().playerController.gameIsSurvivalOrAdventure()) {
					locatorBar();
				}
				break;
			case HOTBAR:
				// Creative / spectator have no XP bar (Post(EXPERIENCE) draws nothing
				// there), so the bar hosts itself after the hotbar -- 1.16.5's split.
				if (mc().playerController != null && !mc().playerController.gameIsSurvivalOrAdventure()) {
					locatorBar();
				}
				break;
			default:
				break;
		}
	}

	/** The top-most layer: every Origin HUD element, then the Tab Editor's list. */
	private void onOverlayPostAll() {
		Gfx g = new Gfx();
		try {
			HudElements.renderAll(g);
		} catch (Throwable t) {
			// the HUD must never take the frame down
		} finally {
			g.pose().popAll();
		}
		// Tab Editor's custom player-list overlay, drawn here (top of the HUD) on our
		// own visibility schedule so it works even on a solo local server, where vanilla
		// refuses to render it. Vanilla's own draw is suppressed in onOverlayPre, so
		// there's never a double. Fail-soft — the HUD must never go down for this.
		if (OriginClientMod.tabListVisible) {
			try {
				Minecraft mc = mc();
				GuiPlayerTabOverlay tab = mc.ingameGUI.getTabList();
				if (tab instanceof GuiPlayerTabOverlayAccessor && mc.thePlayer != null && !mc.gameSettings.hideGUI) {
					GuiPlayerTabOverlayAccessor acc = (GuiPlayerTabOverlayAccessor) tab;
					OriginTabList.render(g, g.guiWidth(), OriginTabList.vanillaPlayerList(mc),
							acc.originclient$getHeader(), acc.originclient$getFooter(), Mods.on("tablist"));
				}
			} catch (Throwable ignored) {
				// never take the HUD down over the tab overlay
			} finally {
				g.pose().popAll();
				Gfx.resetColor();
			}
		}
	}

	/** Saturation mod over the hunger row GuiIngameForge.renderFood just drew. */
	private void saturation(RenderGameOverlayEvent.Post e) {
		try {
			// Bed Wars' Hide Food Bar skips the shanks (its Pre(FOOD) cancel means this
			// Post never fires then) -- checked anyway, as on 1.16.5.
			if (com.origin.client.client.mods.HypixelBedwars.hideFood()) {
				return;
			}
			Minecraft mc = mc();
			if (!(mc.getRenderViewEntity() instanceof EntityPlayer)) {
				return;
			}
			// renderFood drew at height - right_height, then raised right_height by 10.
			int width = e.resolution.getScaledWidth(), height = e.resolution.getScaledHeight();
			int top = height - (GuiIngameForge.right_height - 10);
			SaturationOverlay.render(new Gfx(), (EntityPlayer) mc.getRenderViewEntity(), top, width / 2 + 91);
		} catch (Throwable t) {
			// the hunger bar must never go down over an overlay
		}
	}

	/** Waypoint Locator Bar (world area's WaypointHud), on top of the XP strip. */
	private void locatorBar() {
		try {
			com.origin.client.client.waypoints.WaypointHud.renderBars(new Gfx());
		} catch (Throwable t) {
			// the locator bar must never take the HUD down
		}
	}

	// ---- Potion Effects "Show In Inventory" ----

	@SubscribeEvent
	public void onDrawScreenPost(GuiScreenEvent.DrawScreenEvent.Post e) {
		if (!(e.gui instanceof GuiContainer)) {
			return;
		}
		try {
			if (Mods.on("potionhud") && Mods.bool("potionhud", "showInInventory")) {
				Gfx g = new Gfx();
				try {
					HudElements.renderInInventory(g);
				} finally {
					g.pose().popAll();
				}
			}
		} catch (Throwable t) {
			// the inventory draws without it
		}
	}

	// ---- clicks (CPS + Keystrokes) ----

	// No screen open: Minecraft.runTick posts MouseEvent for every LWJGL mouse event
	// before it routes the click. receiveCanceled: a click another mod swallows was
	// still physically made (1.16.5 recorded at onPress HEAD, before any routing).
	@SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
	public void onMouse(MouseEvent e) {
		if (e.button < 0) {
			return;   // movement / wheel only
		}
		try {
			ClickStats.onButton(e.button, e.buttonstate, mc().currentScreen == null);
		} catch (Throwable ignored) {
		}
	}

	// A screen open: GuiScreen.handleInput drains the mouse queue itself (MouseEvent
	// never fires), posting MouseInputEvent.Pre per event with LWJGL's event state
	// current. Held-state only -- a click in a menu never counts toward CPS.
	@SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
	public void onScreenMouse(GuiScreenEvent.MouseInputEvent.Pre e) {
		try {
			int button = Mouse.getEventButton();
			if (button >= 0) {
				ClickStats.onButton(button, Mouse.getEventButtonState(), false);
			}
		} catch (Throwable ignored) {
		}
	}

	// ---- combat (Tab Editor highlight, Combo, Reach) ----

	@SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
	public void onAttack(AttackEntityEvent e) {
		Minecraft mc = mc();
		// The integrated server posts this too, for its own copy of the player: only
		// the client player's swing counts.
		if (e.entityPlayer == null || e.entityPlayer != mc.thePlayer || e.target == null) {
			return;
		}
		try {
			if (e.target instanceof EntityPlayer) {
				CombatTracker.hit(e.target.getUniqueID());
			}
			// Combo Counter / Reach Display: remember the swing; CombatStats.tick
			// confirms it only once the target visibly takes the hit.
			CombatStats.onAttack(e.target);
		} catch (Throwable ignored) {
			// a stats readout must never interfere with attacking
		}
	}

	// ---- food tooltip ----

	@SubscribeEvent
	public void onTooltip(ItemTooltipEvent e) {
		SaturationOverlay.onTooltip(e.itemStack, e.toolTip);   // 1.8.9 NAME UNVERIFIED: ItemTooltipEvent fields
	}

	// ---- per-tick readout state ----

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent e) {
		if (e.phase != TickEvent.Phase.END) {
			return;
		}
		Minecraft mc = mc();
		// Lunar-parity readouts' per-tick state (Stopwatch keys, Ping samples,
		// Combo/Reach hit confirmation, Minimap keys). Fail-soft: a HUD readout must
		// never be able to throw out of the client tick.
		try {
			Stopwatch.tick(mc);
			PingSampler.tick(mc);
			CombatStats.tick(mc);
			MinimapHud.tick(mc);   // zoom / full-map keys, auto cave mode
		} catch (Throwable t) {
			// keep ticking; the readouts just hold their last value
		}
		// Origin originals: Portal Calculator (fail-soft inside).
		PortalCalc.clientTick(mc);
	}
}
