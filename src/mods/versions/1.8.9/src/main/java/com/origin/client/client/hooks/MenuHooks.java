package com.origin.client.client.hooks;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.OriginHandoff;
import com.origin.client.client.StartupTransition;
import com.origin.client.client.gui.CosmeticsScreen;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginForeignWidgets;
import com.origin.client.client.gui.OriginScreen;
import com.origin.client.client.gui.OriginTitleScreen;
import com.origin.client.client.mixin.menus.GuiScreenAccessor;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginLoadingRenderer;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.shaders.IrisBridge;
import com.origin.client.client.shaders.OriginShaderButton;
import com.origin.client.client.shaders.ShaderBrowserScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenResourcePacks;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.gui.GuiYesNoCallback;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The menus area's Forge-event hooks (registered on both buses by OriginHooks).
 * 1.8.9 stand-ins for the other versions' screen mixins / Fabric screen events:
 *
 *  - GuiOpenEvent: vanilla's GuiMainMenu -> OriginTitleScreen (Main Menu Style
 *    "Origin"), the 1.8.9 form of TitleScreenMixin.
 *  - DrawScreenEvent.Pre: the Origin backdrop behind every out-of-world menu,
 *    drawn before any screen content so it clears the frame (1.16.5's
 *    beforeRender screen event). Vanilla's dirt is cancelled by
 *    GuiScreenBackgroundMixin / GuiSlotMixin.
 *  - InitGuiEvent.Post: the pause menu's Cosmetics row (CosmeticsPauseMixin),
 *    the "Download Shaders" button in OptiFine's shader screen, and other mods'
 *    buttons moved off the Origin title layout (OriginForeignWidgets).
 *  - ActionPerformedEvent.Pre: those buttons' actions (1.8.9 buttons have no
 *    OnPress), and Smart Disconnect (PauseScreenMixin).
 *  - InitGuiEvent.Post on GuiDisconnected: Reconnect (JG-6, the other versions' gui/ReconnectButton).
 *  - ClientTickEvent: claims mc.loadingScreen for the Origin world-load scene,
 *    and says the launcher handshake's "ready" (OriginHandoff).
 *
 * Every handler is fail-soft: a throw leaves vanilla's behaviour.
 */
public class MenuHooks {

	// Actions of the buttons this area adds to vanilla screens (weak: a rebuilt
	// screen's buttons are collected normally).
	private static final Map<GuiButton, Runnable> ACTIONS = new WeakHashMap<>();
	/** Ids for added buttons: far from every vanilla / Forge / OptiFine id. */
	private static final int COSMETICS_ID = 0x4F5243;
	/** Id of the disconnect screen's Reconnect button. */
	private static final int RECONNECT_ID = 0x4F5247;
	/** Address of the server last joined (each connect goes through GuiConnecting); null = none. */
	private static String lastServer;
	/** Id of the plain "Origin" button on the stock title screen. */
	private static final int MODS_ID = 0x4F5244;
	/** Id of the pause menu's "Origin" button (beside "Mods"). */
	private static final int ORIGIN_ID = 0x4F5245;
	/** Id of the pause menu's "Mods" button (Forge's own id 12 would also run Forge's handler). */
	private static final int PAUSE_MODS_ID = 0x4F5246;

	/** Vanilla's pause-menu Disconnect / "Save and Quit to Title" button id. */
	private static final int DISCONNECT_ID = 1;
	// Forge's "Mod Options..." on the pause menu (GuiIngameMenu patch, Forge 11.15.1.2318).
	private static final int FORGE_MOD_OPTIONS_ID = 12;
	/** Vanilla's pause-menu "Open to LAN" button id (GuiIngameMenu.actionPerformed case 7). */
	private static final int LAN_ID = 7;
	/** Vanilla's first-row top padding (room for the "Game menu" title). */
	private static final int TITLE_CLEARANCE = 50;

	// Set while Smart Disconnect replays the confirmed click, so the replay
	// itself is not intercepted again.
	private static boolean replaying = false;

	public MenuHooks() {
	}

	private static boolean originMenuStyle() {
		try {
			return !OriginStyle.vanillaTitle();
		} catch (Throwable t) {
			return false;
		}
	}

	// ---- title screen ----

	// 1.8.9: Forge posts GuiOpenEvent from displayGuiScreen. Whether vanilla's
	// "no screen and no world -> new GuiMainMenu()" substitution runs before or
	// after the event, both shapes are covered: an exact GuiMainMenu, or null
	// with no world. Exact class only, so another mod's custom main menu (a
	// GuiMainMenu subclass) keeps its own. LOWEST priority: a mod that swaps the
	// menu for its own screen wins.
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onGuiOpen(GuiOpenEvent event) {
		try {
			Minecraft mc = Minecraft.getMinecraft();
			boolean title = (event.gui != null && event.gui.getClass() == GuiMainMenu.class)
					|| (event.gui == null && mc.theWorld == null);
			// Right after this event displayGuiScreen clears chat for any GuiMainMenu (Main Menu
			// Style = Vanilla, or another mod's subclass): armed here, skipped by the
			// GuiNewChat hook while Keep Chat History is on. Re-decided on every screen change.
			keepChatArmed = (title || event.gui instanceof GuiMainMenu) && keepChat();
			if (!title || !originMenuStyle() || !OriginScreenRenderer.isActive()
					|| OriginScreen.isBroken(OriginTitleScreen.class)) {
				return;
			}
			event.gui = new OriginTitleScreen();
			// What displayGuiScreen does for an `instanceof GuiMainMenu` screen,
			// which ours is not: hide the debug overlay and clear the chat -- unless
			// Keep Chat History is on (1.21.1 keeps chat and the Up-arrow history too).
			keepChatArmed = false;
			mc.gameSettings.showDebugInfo = false;
			if (mc.ingameGUI != null && !keepChat()) {
				mc.ingameGUI.getChatGUI().clearChatMessages();
			}
		} catch (Throwable t) {
			// vanilla's main menu
		}
	}

	// ---- Keep Chat History (the Chat mod's row, default on) ----

	// Set by onGuiOpen when vanilla's own title-screen chat clear is about to run; spent by that
	// clear or dropped at the end of the tick (onClientTick). Render thread only.
	private static boolean keepChatArmed = false;

	private static boolean keepChat() {
		return Mods.on("chat") && Mods.bool("chat", "keepHistory");
	}

	/** GuiNewChat.clearChatMessages HEAD: true = skip this clear (the title-screen one, with Keep Chat History on). */
	public static boolean skipChatClear() {
		boolean skip = keepChatArmed;
		keepChatArmed = false;
		return skip;
	}

	// ---- out-of-world backdrop ----

	// One drawing face for the backdrop, reused every frame (Gfx is stateless
	// beyond its Pose, which the backdrop leaves balanced). Render thread only.
	private static final Gfx MENU_GFX = new Gfx();

	@SubscribeEvent
	public void onDrawScreenPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
		try {
			GuiScreen screen = event.gui;
			if (screen == null || Minecraft.getMinecraft().theWorld != null
					|| !OriginScreenRenderer.isActive()
					|| OriginStyle.vanillaMenusOn(screen)) {
				return;
			}
			// Screens that paint their own scene: every Origin screen (the title
			// draws the panorama itself; the mod menu, the HUD editor and every
			// OriginWindow draw renderTitleBackground), and vanilla's main menu (its
			// own panorama, Main Menu Style = Vanilla).
			if (screen instanceof OriginScreen || screen instanceof GuiMainMenu) {
				return;
			}
			// The clean look (2026-10-19): this version's own panorama under the menu
			// dim. GuiScreenBackgroundMixin then drops vanilla's dirt for the frame.
			OriginScreenRenderer.renderMenuBackdrop(MENU_GFX);
		} catch (Throwable t) {
			// the backdrop must never take the frame down
		}
	}

	// ---- buttons added to vanilla / OptiFine screens ----

	// LOWEST: runs after other mods' InitGuiEvent handlers, so buttons they add
	// are already in the list (measured by the pause row, moved off the title's).
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onInitGuiPost(GuiScreenEvent.InitGuiEvent.Post event) {
		GuiScreen screen = event.gui;
		if (screen == null || event.buttonList == null) {
			return;
		}
		if (screen instanceof OriginTitleScreen) {
			// Another mod's button that landed on Origin's title layout is moved
			// to free space (TitleScreenMixin's avoidOverlap call on the others).
			OriginForeignWidgets.avoidOverlap(screen, event.buttonList);
		} else if (screen.getClass() == GuiMainMenu.class && OriginStyle.vanillaTitle()) {
			addVanillaModsEntry(event.buttonList);
		} else if (screen instanceof GuiIngameMenu) {
			addPauseModsRow(screen, event.buttonList);
			addCosmeticsRow(screen, event.buttonList);
		} else if (IrisBridge.isShaderScreen(screen)) {
			addDownloadShaders(screen, event.buttonList);
		} else if (screen instanceof GuiScreenResourcePacks) {
			addBrowsePacks(screen, event.buttonList);
		} else if (screen instanceof GuiConnecting) {
			noteServer();
		} else if (screen instanceof GuiDisconnected) {
			addReconnect(screen, event.buttonList);
		}
	}

	/** A connect began: GuiConnecting's constructor has just set the server it joins. */
	private static void noteServer() {
		try {
			ServerData sd = Minecraft.getMinecraft().getCurrentServerData();
			lastServer = sd != null && sd.serverIP != null && !sd.serverIP.trim().isEmpty() ? sd.serverIP.trim() : null;
		} catch (Throwable t) {
			lastServer = null;
		}
	}

	/**
	 * "Reconnect" on the disconnect screen (JG-6): after a kick, a lost connection or a failed
	 * connect, one click joins the same server again (Social.join, the Friends window's Join),
	 * under the screen's last button. No button when no server is known (singleplayer).
	 * Fail-soft: the screen stays vanilla.
	 */
	private static void addReconnect(GuiScreen screen, List<GuiButton> buttons) {
		try {
			final String server = lastServer;
			if (server == null) {
				return;
			}
			GuiButton last = null;
			for (GuiButton b : buttons) {
				if (last == null || b.yPosition > last.yPosition) {
					last = b;
				}
			}
			int bw = last != null ? last.width : 200;
			int bh = last != null ? last.height : 20;
			int x = last != null ? last.xPosition : screen.width / 2 - bw / 2;
			int y = last != null ? last.yPosition + bh + 4 : screen.height / 2 + 30;
			if (y + bh > screen.height - 4 && last != null) {
				y = last.yPosition - bh - 4;   // no room under it (a tiny window): above it
			}
			GuiButton reconnect = new GuiButton(RECONNECT_ID, x, y, bw, bh, "Reconnect");
			buttons.add(reconnect);
			ACTIONS.put(reconnect, new Runnable() {
				@Override
				public void run() {
					com.origin.client.client.social.Social.join(server);
				}
			});
		} catch (Throwable ignored) {
			// fail-soft: the disconnect screen stays vanilla
		}
	}

	/**
	 * The pause menu's "Mods | Origin" row (Will, 2026-11-04, as every Fabric version): "Mods" opens
	 * Forge's GuiModList (every mod, each with its Config button) and "Origin" opens Origin's own menu.
	 * Forge's GuiIngameMenu patch (CI dev-shots 2026-11-04) puts its "Mod Options..." (id 12) in the
	 * right half of the Options row and stretches Open to LAN (id 7) across the row above. So the two
	 * swap places: LAN takes Forge's half slot (a new id-7 button, so vanilla's own actionPerformed
	 * still opens Share to LAN; enabled copied, it is off on a server), and the full row splits into
	 * Mods | Origin, 4 px apart like vanilla's pairs: the 1.21.1 layout. If id 12 already spans a row
	 * on its own, that row splits instead. Clicks go through ACTIONS (onActionPerformedPre).
	 * Fail-soft: any surprise leaves Forge's buttons as they were.
	 */
	private static void addPauseModsRow(final GuiScreen pause, List<GuiButton> buttons) {
		try {
			GuiButton forge = null, lan = null;
			for (GuiButton b : buttons) {
				if (b.id == FORGE_MOD_OPTIONS_ID) {
					forge = b;
				} else if (b.id == LAN_ID) {
					lan = b;
				}
			}
			if (forge == null) {
				return;
			}
			GuiButton row = forge;
			if (lan != null && lan.getButtonWidth() > forge.getButtonWidth()) {
				GuiButton halfLan = new GuiButton(LAN_ID, forge.xPosition, forge.yPosition, forge.getButtonWidth(), 20, lan.displayString);
				halfLan.enabled = lan.enabled;
				halfLan.visible = lan.visible;
				row = lan;
				buttons.remove(lan);
				buttons.add(halfLan);
			}
			int w = row.getButtonWidth();
			int half = (w - 4) / 2;
			GuiButton mods = new GuiButton(PAUSE_MODS_ID, row.xPosition, row.yPosition, half, 20, "Mods");
			GuiButton origin = new GuiButton(ORIGIN_ID, row.xPosition + w - half, row.yPosition, half, 20, "Origin");
			buttons.remove(forge);
			buttons.add(mods);
			buttons.add(origin);
			ACTIONS.put(mods, new Runnable() {
				@Override
				public void run() {
					Minecraft.getMinecraft().displayGuiScreen(new net.minecraftforge.fml.client.GuiModList(pause));
				}
			});
			ACTIONS.put(origin, new Runnable() {
				@Override
				public void run() {
					Minecraft.getMinecraft().displayGuiScreen(
							new com.origin.client.client.gui.OriginModMenuScreen(pause, null));
				}
			});
		} catch (Throwable ignored) {
			// Forge's buttons as they were
		}
	}

	/**
	 * The stock title screen has no way into the mod menu (Right Shift does nothing while a
	 * screen is open), so a plain vanilla-sized button in the top-left corner opens it. Whatever
	 * Button Style says, it is an ordinary GuiButton. Fail-soft: the stock title stays as it is.
	 */
	private static void addVanillaModsEntry(List<GuiButton> buttons) {
		try {
			GuiButton entry = new GuiButton(MODS_ID, 4, 4, 80, 20, "Origin");
			buttons.add(entry);
			ACTIONS.put(entry, new Runnable() {
				@Override
				public void run() {
					Minecraft.getMinecraft().displayGuiScreen(new com.origin.client.client.gui.OriginModMenuScreen());
				}
			});
		} catch (Throwable ignored) {
			// the stock title as it is
		}
	}

	/**
	 * The Resource Pack Hub's entry point: a "Browse Packs" button in the top-left
	 * of Minecraft's own Resource Packs screen, the same button and spot 1.21.1
	 * uses (and the shader screen's "Download Shaders"). 1.8.9: the screen is
	 * resource-packs only (no data-pack twin to tell apart), and it re-inits on
	 * return from the hub, so the button is added once per init.
	 */
	private static void addBrowsePacks(final GuiScreen screen, List<GuiButton> buttons) {
		try {
			for (GuiButton b : buttons) {
				if (b instanceof OriginShaderButton) {
					return;
				}
			}
			buttons.add(new OriginShaderButton(6, 6, 120, 20, "Browse Packs", new Runnable() {
				@Override
				public void run() {
					Minecraft.getMinecraft().displayGuiScreen(
							new com.origin.client.client.packs.ResourcePackHubScreen(screen));
				}
			}));
		} catch (Throwable ignored) {
			// the Resource Packs screen as it is
		}
	}

	/**
	 * Adds a "Cosmetics" row under the pause menu's own buttons (1.16.5's
	 * CosmeticsPauseMixin), so a player can change what they wear without
	 * leaving the world. Indistinguishable from its neighbours: a plain GuiButton
	 * (skinned like every other pause button), its bounds COPIED from the menu's
	 * own full-width row with the menu's own row gap. The stack is lifted by a
	 * quarter row, as 1.21.1 does, never above the title's 50 px clearance.
	 * 1.8.9: Forge's own "Mod Options..." button is part of this menu and is
	 * measured like the rest. Fail-soft: if the layout can't be read, the menu
	 * stays vanilla.
	 */
	private static void addCosmeticsRow(final GuiScreen screen, List<GuiButton> buttons) {
		try {
			if (buttons.isEmpty()) {
				return;
			}
			GuiButton wide = buttons.get(0);
			GuiButton low = buttons.get(0);
			int top = Integer.MAX_VALUE;
			for (GuiButton b : buttons) {
				if (b.width > wide.width) wide = b;
				if (b.yPosition + b.height > low.yPosition + low.height) low = b;
				top = Math.min(top, b.yPosition);
			}
			// The menu's own row gap: distance from the lowest row to the row above it.
			int gap = 4;
			int above = Integer.MIN_VALUE;
			for (GuiButton b : buttons) {
				int bottom = b.yPosition + b.height;
				if (bottom <= low.yPosition) above = Math.max(above, bottom);
			}
			if (above != Integer.MIN_VALUE) gap = low.yPosition - above;
			int h = wide.height;
			int lift = Math.max(0, Math.min((h + gap) / 4, top - TITLE_CLEARANCE));
			for (GuiButton b : buttons) {
				b.yPosition -= lift;
			}
			GuiButton cosmetics = new GuiButton(COSMETICS_ID, wide.xPosition, low.yPosition + low.height + gap,
					wide.width, h, "Cosmetics");
			buttons.add(cosmetics);
			ACTIONS.put(cosmetics, new Runnable() {
				@Override
				public void run() {
					Minecraft.getMinecraft().displayGuiScreen(new CosmeticsScreen(screen));
				}
			});
		} catch (Throwable ignored) {
			// fail-soft: the pause menu stays vanilla
		}
	}

	/**
	 * The "Download Shaders" entry point inside OptiFine's own shader screen, so
	 * the whole shader flow lives where players already look (1.16.5 adds it to
	 * Iris's screen). Detected by class name -- no compile dependency on
	 * OptiFine, no OptiFine mixin to break on updates. OptiFine re-inits its
	 * screen on resize and on return from the browser, which rebuilds the list,
	 * so the button is added once per init (and never twice).
	 */
	private static void addDownloadShaders(final GuiScreen screen, List<GuiButton> buttons) {
		try {
			for (GuiButton b : buttons) {
				if (b instanceof OriginShaderButton) {
					return;
				}
			}
			buttons.add(new OriginShaderButton(6, 6, 120, 20, "Download Shaders", new Runnable() {
				@Override
				public void run() {
					Minecraft.getMinecraft().displayGuiScreen(new ShaderBrowserScreen(screen));
				}
			}));
		} catch (Throwable ignored) {
			// OptiFine's screen as it is
		}
	}

	@SubscribeEvent
	public void onActionPerformedPre(GuiScreenEvent.ActionPerformedEvent.Pre event) {
		try {
			GuiButton button = event.button;
			if (button == null) {
				return;
			}
			Minecraft mc = Minecraft.getMinecraft();
			if (button instanceof OriginShaderButton) {
				button.playPressSound(mc.getSoundHandler());
				event.setCanceled(true);
				((OriginShaderButton) button).onPress();
				return;
			}
			Runnable action = ACTIONS.get(button);
			if (action != null) {
				button.playPressSound(mc.getSoundHandler());
				event.setCanceled(true);
				action.run();
				return;
			}
			if (event.gui instanceof GuiIngameMenu && button.id == DISCONNECT_ID) {
				smartDisconnect(event, mc);
			}
		} catch (Throwable t) {
			// vanilla handles the click
		}
	}

	/**
	 * SETTINGS > General > Smart Disconnect (1.16.5's PauseScreenMixin): the
	 * pause menu's "Save and Quit to Title" / "Disconnect" click asks first, so a
	 * stray click can't drop you out of a world or off a server. The vanilla
	 * action is preserved verbatim: on Yes, the pause screen's own
	 * actionPerformed runs with the original button (GuiScreenAccessor); on No,
	 * the pause menu comes back.
	 * 1.8.9: the click is intercepted (ActionPerformedEvent.Pre is cancelable)
	 * instead of swapping the button, since a GuiButton carries no action of its own.
	 */
	private static void smartDisconnect(GuiScreenEvent.ActionPerformedEvent.Pre event, final Minecraft mc) {
		if (replaying || !Mods.bool(Mods.GENERAL_ID, "smartDisconnect")
				|| !(event.gui instanceof GuiScreenAccessor)) {
			return;
		}
		final GuiScreen pause = event.gui;
		final GuiButton original = event.button;
		event.button.playPressSound(mc.getSoundHandler());
		event.setCanceled(true);
		mc.displayGuiScreen(new GuiYesNo(new GuiYesNoCallback() {
			@Override
			public void confirmClicked(boolean confirmed, int id) {
				if (!confirmed) {
					mc.displayGuiScreen(pause);
					return;
				}
				replaying = true;
				try {
					((GuiScreenAccessor) pause).originclient$actionPerformed(original);
				} catch (Throwable t) {
					com.origin.client.OriginClient.LOGGER.error("Origin: Smart Disconnect could not leave the world", t);
					mc.displayGuiScreen(pause);
				} finally {
					replaying = false;
				}
			}
		}, "Leave this world?", "You'll disconnect from the current world or server.", 0));
	}

	// ---- world-load screen ----

	/**
	 * Claims mc.loadingScreen for the Origin loading scene, and RE-claims it if
	 * anything replaces it: OptiFine installs its own LoadingScreenRenderer after
	 * mod init (and can re-create it), which is why a one-shot swap left the
	 * world-load screen vanilla on the pre-port module. Once per client tick, a
	 * field read when nothing changed. Skipped while Origin rendering is broken,
	 * and the renderer itself delegates to vanilla then.
	 */
	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) {
			return;
		}
		// The title-screen chat clear runs in the same displayGuiScreen call that armed the skip,
		// so by the end of the tick it is either spent or never coming: another mod cancelled the
		// title (or swapped it after us). Disarmed here, so a later, deliberate clear is never
		// skipped (B189PRE-5).
		keepChatArmed = false;
		try {
			OriginHandoff.tick();
		} catch (Throwable t) {
			// informational only
		}
		try {
			if (Minecraft.getMinecraft().isSingleplayer()) {
				lastServer = null;   // a singleplayer world: its disconnect screen offers no Reconnect
			}
		} catch (Throwable t) {
			// Reconnect is optional
		}
		try {
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.theWorld != null) {
				// A launch straight into a server: the startup intro is not owed later.
				StartupTransition.claimIntro();
			}
			if (OriginStyle.vanillaMenus()) {
				// vanilla mode: Mojang's own loading screen (put back if Origin's was installed earlier)
				if (mc.loadingScreen instanceof OriginLoadingRenderer) {
					mc.loadingScreen = new net.minecraft.client.LoadingScreenRenderer(mc);
				}
			} else if (mc.loadingScreen != null && !(mc.loadingScreen instanceof OriginLoadingRenderer)
					&& OriginScreenRenderer.isActive()) {
				mc.loadingScreen = new OriginLoadingRenderer(mc);
			}
		} catch (Throwable t) {
			// vanilla's loading screen stays
		}
	}
}
