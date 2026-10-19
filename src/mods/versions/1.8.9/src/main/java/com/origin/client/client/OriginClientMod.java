package com.origin.client.client;

import com.origin.client.client.hooks.OriginHooks;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.util.KeyCodes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

// Client entrypoint + per-tick feature hub. All feature state lives in the
// Mods registry (originclient-mods.json); the legacy FEATURES object stays
// only as the migration source and for its transient sprint/sneak state.
//
// 1.8.9: OriginClient (the @Mod) calls init(); the hub registers itself on the
// Forge buses through OriginHooks (TickEvent.ClientTickEvent END = 1.16.5's
// END_CLIENT_TICK), then every area's hooks class. Unlike 1.16.5's hub it does
// NOT init or tick other areas' features -- each area does that from its own
// hooks/<Area>Hooks so the areas land and fail independently. What stays here
// is the hub's own list: zoom/freelook latches, toggle sprint/sneak, chat
// background/scale, hitboxes switch, Full Bright / scoreboard / nametag / chunk
// border keys, time changer input, fly boost, weather thunder sounds, the
// waypoint keybinds + death-waypoint edge, the Tab key sticky + sync, General
// settings, and the on-exit flush.
public class OriginClientMod {
	public static final OriginFeatures FEATURES = OriginConfig.load();

	private static OriginClientMod instance;

	private boolean freelookWasDown = false;
	private int savedPerspective = -1;
	private boolean chatApplied = false;
	private float savedChatScale = 1.0f;
	private float appliedChatScale = -1f;
	private boolean hitboxesApplied = false;
	private boolean chunkKeyWasDown = false;
	private boolean sprintKeyWasDown = false, sneakKeyWasDown = false;
	private boolean scoreboardKeyWasDown = false;
	private boolean fullbrightKeyWasDown = false;
	private boolean zoomKeyWasDown = false;
	private boolean timeIncWasDown = false, timeDecWasDown = false;
	// static so previewTimeSlider (called from the mod menu) can fold it in too.
	private static double timePassageAccum = 0;

	// Live time-changer output, read by the world area's time hook every frame.
	public static volatile double timeOverride = 6000;
	// Toggle-mode zoom latch. zoomActive = zoom is engaged this tick (the target
	// the world area's FOV hook eases toward, frame-side). zoomScrollFactor is a
	// transient scroll-to-zoom multiplier, reset whenever zoom disengages.
	public static volatile boolean zoomToggled = false;
	public static volatile boolean zoomActive = false;
	public static volatile double zoomScrollFactor = 1.0;
	// Nametag toggle latches, read by the world area's nametag hook.
	public static volatile boolean nametagsHidden = false, playerNametagsHidden = false;
	// Whether the Tab Editor's custom player-list overlay should draw this frame. Set
	// each tick; read by the HUD area. Origin drives its own visibility (not
	// vanilla's, which refuses to show the list on a solo local server) so it also
	// previews in single-player and never double-draws with vanilla.
	public static volatile boolean tabListVisible = false;
	private boolean nametagAllKeyWasDown = false, nametagPlayerKeyWasDown = false;

	// Tab Editor: sticky tap-vs-hold state + two-way keybind sync bookkeeping.
	private boolean tabKeyWasDown = false;
	private boolean tabStickyOn = false;
	private long tabPressAt = 0;
	private boolean tabSyncInit = false;
	private int lastOurTabKey = -1, lastMcTabKey = -1;
	// Zoomed Sensitivity + Smooth Camera (zoom/freelook) save-and-restore state.
	private boolean zoomSensApplied = false;
	private float savedSensitivity = 0.5f;
	private boolean smoothCamApplied = false;
	private boolean savedSmoothCam = false;
	// Fly Boost restore latch + thunder-sound cadence counter.
	private boolean flyBoostApplied = false;
	private int thunderSoundTicks = 0;
	// Edge-trigger for death waypoints: true while the player is alive, so a death
	// drops exactly one waypoint on the alive->dead transition.
	private boolean wasAlive = false;
	// Latched fail-soft: a hub section that throws is switched off for the session.
	private boolean tickBroken = false;

	/** Called once by OriginClient (FMLInitializationEvent). */
	public static void init() {
		OriginDevShots.applyStyleOverride();   // dev-only (env ORIGIN_SHOTS_STYLE); no-op for players
		OriginKeyBindings.register();
		instance = new OriginClientMod();
		OriginHooks.registerObject(instance);
		// Every area's hooks class (Menu, ModMenu, Hud, World, Social, Server).
		OriginHooks.registerAll();
		// Resource Pack Hub: finish uninstalls that had to wait for Minecraft to
		// release a pack (now, and after every resource reload). Its "Browse Packs"
		// button on the Resource Packs screen is added by MenuHooks.
		try {
			com.origin.client.client.packs.PackHub.register();
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin pack hub: init failed", t);
		}
		// 1.8.9: Forge 1.8.9 has no client-stopping event. Minecraft.shutdown()
		// ends in System.exit, which runs JVM shutdown hooks: the single
		// authoritative autosave happens there (mod settings + HUD positions,
		// legacy flags, vanilla options). Every store already saves eagerly.
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
				@Override
				public void run() {
					onClientStopping(Minecraft.getMinecraft());
				}
			}, "Origin config flush"));
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: no exit flush hook (settings still save on change)", t);
		}
	}

	/** Called once by OriginClient (FMLPostInitializationEvent): nothing yet. */
	public static void postInit() {
	}

	// ---- the hub tick ----

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END || tickBroken) {
			return;
		}
		try {
			onEndTick(Minecraft.getMinecraft());
		} catch (Throwable t) {
			// Mandate 4: a hub bug turns the hub off, never the game.
			tickBroken = true;
			com.origin.client.OriginClient.LOGGER.error("Origin tick hub failed; its features are off for this session", t);
		}
		// Vanilla options save the moment they change (Settings Sync needs it);
		// Zoom's sensitivity / smooth camera / perspective are not the player's
		// settings, so options.txt waits until they are put back.
		OptionsAutosave.held = zoomSensApplied || smoothCamApplied || savedPerspective >= 0;
		OptionsAutosave.tick(Minecraft.getMinecraft());
	}

	// Flush everything to disk once, when Minecraft is shutting down. Each
	// store is saved in its own try so a failure in one never blocks the
	// others, and nothing here is allowed to throw into the shutdown path.
	private static void onClientStopping(Minecraft client) {
		try {
			// a slider mid-drag or a half-typed text field in the mod menu
			if (client != null && client.currentScreen instanceof com.origin.client.client.gui.OriginModMenuScreen) {
				((com.origin.client.client.gui.OriginModMenuScreen) client.currentScreen).commitPendingEdits();
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: failed to keep the mod menu's last edit on exit", t);
		}
		try {
			Mods.flush();                     // mod settings + HUD positions -> originclient-mods.json
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: failed to save mod settings on exit", t);
		}
		try {
			OriginConfig.save(FEATURES);      // legacy feature flags -> originclient.json
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: failed to save feature flags on exit", t);
		}
		try {
			if (client != null && client.gameSettings != null) {
				// put back any value the hub swapped in for the moment, then save
				OriginClientMod hub = instance;
				if (hub != null) {
					hub.restoreTransientOptions(client);
				}
				client.gameSettings.saveOptions();   // vanilla game options -> options.txt
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: failed to save game options on exit", t);
		}
	}

	// Zoom sensitivity / smooth camera / perspective are swapped in only while a
	// key is held; never let a quit mid-zoom write the temporary value to disk.
	private void restoreTransientOptions(Minecraft client) {
		if (zoomSensApplied) {
			client.gameSettings.mouseSensitivity = savedSensitivity;
		}
		if (smoothCamApplied) {
			client.gameSettings.smoothCamera = savedSmoothCam;
		}
		if (savedPerspective >= 0) {
			client.gameSettings.thirdPersonView = savedPerspective;
		}
		if (chatApplied) {
			client.gameSettings.chatScale = savedChatScale;
		}
	}

	/** Raw key state for the mod-menu's rebindable keys. 1.8.9: an LWJGL2 code
	 *  (what Mods.keyCode returns; mouse = -100 + button; -1 / 0 = unbound). */
	public static boolean isRawKeyDown(int lwjglKey) {
		return KeyCodes.isDown(lwjglKey);
	}

	/**
	 * True while Toggle Sprint is engaged -- mixin/core/KeyBindingMixin then
	 * reports the vanilla sprint key as held, so the GAME starts the sprint
	 * itself, at the exact point in the tick it normally would.
	 *
	 * That is what gives the toggle its memory. A screen, a pause, a server hop
	 * or a wall bump ends the sprint exactly the way vanilla always did, and
	 * vanilla re-starts it on the first tick the player is moving again -- so it
	 * never needs re-toggling; only an un-toggle turns it off. Every vanilla
	 * rule stays in force too: no sprinting inside a screen, at low hunger, or
	 * while eating/blocking.
	 */
	public static boolean sprintKeyHeldByToggle(KeyBinding key) {
		if (!FEATURES.sprintToggledOn) {
			return false;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || mc.gameSettings == null || key != mc.gameSettings.keyBindSprint) {
			return false;
		}
		if (mc.currentScreen != null) {
			return false;   // vanilla releases every key in a screen; don't lie to it
		}
		return Mods.on("togglesprint") && Mods.bool("togglesprint", "sprint");
	}

	/**
	 * 1.8.9: toggle sneak the same way. 1.16.5 set the player's shift state
	 * directly; on 1.8.9 MovementInputFromOptions re-reads the sneak KEY every
	 * tick, so the toggle makes that key read as held (KeyBindingMixin) and the
	 * game sneaks by its own rules. Releasing the toggle simply stops lying.
	 */
	public static boolean sneakKeyHeldByToggle(KeyBinding key) {
		if (!FEATURES.sneakToggledOn) {
			return false;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || mc.gameSettings == null || key != mc.gameSettings.keyBindSneak) {
			return false;
		}
		if (mc.currentScreen != null) {
			return false;
		}
		return Mods.on("togglesprint") && Mods.bool("togglesprint", "sneak");
	}

	/** The one question KeyBindingMixin asks. */
	public static boolean keyHeldByToggle(KeyBinding key) {
		try {
			return sprintKeyHeldByToggle(key) || sneakKeyHeldByToggle(key);
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * 1.8.9: the chat line background colour (GuiNewChat.drawChat's per-line
	 * drawRect, hooked by mixin/core/GuiNewChatMixin). 1.8.9 ties that box to the
	 * text alpha (vanilla: half the line's text alpha) and has no separate
	 * "text background opacity" option (1.14+), so the Chat mod's Background
	 * Opacity is applied here: the line's text alpha x the slider. The default
	 * 0.5 reproduces vanilla exactly. Mod off -> vanilla's colour untouched.
	 */
	public static int chatBackground(int vanillaColor) {
		try {
			if (!Mods.on("chat")) {
				return vanillaColor;
			}
			int textAlpha = Math.min(255, ((vanillaColor >>> 24) & 0xFF) * 2);
			double op = Math.max(0.0, Math.min(1.0, Mods.num("chat", "opacity")));
			int a = (int) Math.round(textAlpha * op);
			return (a << 24) | (vanillaColor & 0xFFFFFF);
		} catch (Throwable t) {
			return vanillaColor;
		}
	}

	private void onEndTick(Minecraft client) {
		// Dev-only screenshot matrix (env ORIGIN_SHOTS) — see OriginDevShots.
		if (OriginDevShots.enabled()) {
			OriginDevShots.tick(client);
		}
		// 1.8.9: Right Shift (OriginKeyBindings.openModMenu) is consumed by the
		// modmenu area's ModMenuHooks, which opens the HUD editor screen.

		// Copy Coords To Clipboard: press the (unbound-by-default) key to drop
		// your XYZ on the clipboard, gated by the Coordinates mod + its toggle.
		while (OriginKeyBindings.copyCoords.isPressed()) {
			EntityPlayerSP p = client.thePlayer;
			if (p != null && Mods.on("coords") && Mods.bool("coords", "copyClipboard")) {
				String c = MathHelper.floor_double(p.posX) + ", " + MathHelper.floor_double(p.posY) + ", "
						+ MathHelper.floor_double(p.posZ);
				GuiScreen.setClipboardString(c);
				actionBar(client, "Copied coordinates: " + c);
			}
		}

		// Waypoints — three activation paths (in addition to the mod-card ENABLED
		// toggle and the in-menu toggle in WaypointScreen).
		while (OriginKeyBindings.waypointToggle.isPressed()) {
			Mods.setOn("waypoints", !Mods.on("waypoints"));
		}
		while (OriginKeyBindings.waypointMenu.isPressed()) {
			if (client.currentScreen == null) {
				client.displayGuiScreen(new com.origin.client.client.waypoints.WaypointScreen());
			}
		}
		while (OriginKeyBindings.waypointCreate.isPressed()) {
			// Opens the Waypoints screen straight into a fresh Create form.
			if (client.currentScreen == null) {
				client.displayGuiScreen(new com.origin.client.client.waypoints.WaypointScreen(true));
			}
		}
		// Death waypoint: drop one on the alive->dead transition (edge-triggered).
		EntityPlayerSP wp = client.thePlayer;
		boolean dead = wp != null && (wp.isDead || wp.getHealth() <= 0f);
		if (dead && wasAlive && Mods.on("waypoints") && Mods.bool("waypoints", "deathWaypoints")) {
			com.origin.client.client.waypoints.Waypoints.onDeath(MathHelper.floor_double(wp.posX),
					MathHelper.floor_double(wp.posY), MathHelper.floor_double(wp.posZ),
					com.origin.client.client.waypoints.Waypoints.currentDim());
		}
		wasAlive = wp != null && !dead;

		applyChat(client);
		applyHitboxes(client);
		applyWeather(client);
		applyTimeChanger(client);
		GeneralSettings.tick(client);

		EntityPlayerSP player = client.thePlayer;
		if (player == null) {
			return;
		}

		// Scoreboard hide toggle keybind (+ optional action-bar message)
		boolean sbDown = client.currentScreen == null && isRawKeyDown(Mods.keyCode("scoreboard", "toggleKey"));
		if (sbDown && !scoreboardKeyWasDown) {
			boolean hidden = !Mods.bool("scoreboard", "hideScoreboard");
			Mods.set("scoreboard", "hideScoreboard", hidden);
			if (Mods.bool("scoreboard", "displayToggleMessage")) {
				actionBar(client, hidden ? "Scoreboard hidden" : "Scoreboard shown");
			}
		}
		scoreboardKeyWasDown = sbDown;

		// Full Bright toggle keybind flips the mod's own sub-toggle
		boolean fbDown = client.currentScreen == null && isRawKeyDown(Mods.keyCode("fullbright", "key"));
		if (fbDown && !fullbrightKeyWasDown) {
			Mods.set("fullbright", "fullBright", !Mods.bool("fullbright", "fullBright"));
		}
		fullbrightKeyWasDown = fbDown;

		// Nametag toggle keybinds (default unbound). Toggle All hides every
		// nametag; Toggle Players hides only other players'.
		boolean naDown = client.currentScreen == null && isRawKeyDown(Mods.keyCode("nametags", "toggleAll"));
		if (naDown && !nametagAllKeyWasDown) {
			nametagsHidden = !nametagsHidden;
			if (Mods.bool("nametags", "displayToggleMessage")) {
				actionBar(client, nametagsHidden ? "Nametags hidden" : "Nametags shown");
			}
		}
		nametagAllKeyWasDown = naDown;

		boolean npDown = client.currentScreen == null && isRawKeyDown(Mods.keyCode("nametags", "togglePlayers"));
		if (npDown && !nametagPlayerKeyWasDown) {
			playerNametagsHidden = !playerNametagsHidden;
			if (Mods.bool("nametags", "displayToggleMessage")) {
				actionBar(client, playerNametagsHidden ? "Player nametags hidden" : "Player nametags shown");
			}
		}
		nametagPlayerKeyWasDown = npDown;

		tickTabList(client);

		// Toggle Zoom: in toggle mode the key latches instead of holding. The
		// FOV easing itself lives in the world area's FOV hook (frame-side); here
		// we only resolve whether zoom is engaged and expose it as zoomActive.
		boolean zDown = client.currentScreen == null
				&& (isRawKeyDown(Mods.keyCode("zoom", "key")) || OriginKeyBindings.zoom.isKeyDown());
		if (Mods.on("zoom") && Mods.bool("zoom", "toggleZoom")) {
			if (zDown && !zoomKeyWasDown) {
				zoomToggled = !zoomToggled;
			}
		} else {
			zoomToggled = false;
		}
		zoomKeyWasDown = zDown;
		zoomActive = Mods.on("zoom") && (Mods.bool("zoom", "toggleZoom") ? zoomToggled : zDown);
		if (!zoomActive) {
			zoomScrollFactor = 1.0;   // reset scroll-zoom depth when not zooming
		}

		// Zoomed Sensitivity: temporarily scale mouse sensitivity while zoomed so
		// aiming is steadier, restoring the player's own value the instant zoom ends.
		if (zoomActive) {
			if (!zoomSensApplied) {
				savedSensitivity = client.gameSettings.mouseSensitivity;
				zoomSensApplied = true;
			}
			double f = Mods.num("zoom", "sensitivity");
			client.gameSettings.mouseSensitivity = (float) Math.max(0.0, Math.min(1.0, savedSensitivity * (f <= 0 ? 1.0 : f)));
		} else if (zoomSensApplied) {
			client.gameSettings.mouseSensitivity = savedSensitivity;
			zoomSensApplied = false;
		}

		// Chunk borders toggle keybind (edge-triggered, ignored in screens).
		int cbKey = Mods.keyCode("chunkborders", "key");
		boolean cbDown = client.currentScreen == null && isRawKeyDown(cbKey);
		if (cbDown && !chunkKeyWasDown) {
			Mods.setOn("chunkborders", !Mods.on("chunkborders"));
		}
		chunkKeyWasDown = cbDown;

		// Toggle Sprint / Sneak. "Hold" mode = active only while the mod's key is
		// held; "Toggle" flips on the mod's key. Toggle happens ONLY on the mod's
		// assigned key — the vanilla sprint/sneak keys are never consumed, so
		// double-tap-W sprinting and hold-Ctrl / hold-Shift keep working normally.
		// While a toggle is on, the vanilla key simply reads as held
		// (KeyBindingMixin) so the game drives sprint/sneak itself.
		boolean toggleMod = Mods.on("togglesprint");
		boolean holdMode = Mods.mode("togglesprint", "mode").equals("Hold");
		if (toggleMod && Mods.bool("togglesprint", "sprint")) {
			boolean custom = client.currentScreen == null && isRawKeyDown(Mods.keyCode("togglesprint", "key"));
			boolean edge = custom && !sprintKeyWasDown;
			sprintKeyWasDown = custom;
			if (holdMode) {
				FEATURES.sprintToggledOn = custom;
			} else if (edge) {
				FEATURES.sprintToggledOn = !FEATURES.sprintToggledOn;
			}
		} else {
			FEATURES.sprintToggledOn = false;
		}

		if (toggleMod && Mods.bool("togglesprint", "sneak")) {
			boolean custom = client.currentScreen == null && isRawKeyDown(Mods.keyCode("togglesprint", "key"));
			boolean edge = custom && !sneakKeyWasDown;
			sneakKeyWasDown = custom;
			if (holdMode) {
				FEATURES.sneakToggledOn = custom;
			} else if (edge) {
				FEATURES.sneakToggledOn = !FEATURES.sneakToggledOn;
			}
		} else {
			FEATURES.sneakToggledOn = false;
		}

		// Fly Boost: multiply flight speed, but ONLY in a mode that actually
		// allows flight (creative/spectator) and only while airborne — it never
		// touches survival movement, so it's a QoL tweak, not a speed hack.
		if (Mods.on("togglesprint") && Mods.bool("togglesprint", "flyBoost")
				&& player.capabilities.isFlying && (player.capabilities.isCreativeMode || player.isSpectator())) {
			float mult = (float) Math.max(1.0, Mods.num("togglesprint", "flyBoostAmount"));
			player.capabilities.setFlySpeed(0.05f * mult);
			flyBoostApplied = true;
		} else if (flyBoostApplied) {
			player.capabilities.setFlySpeed(0.05f);
			flyBoostApplied = false;
		}

		// Freelook: custom bind (default Left Alt) with the vanilla-controls
		// binding as a fallback. Hold = look around; release = snap back (the
		// player's real rotation is never touched — the world area's camera hook
		// renders OriginFreelookState instead). "Toggle Freelook" latches.
		boolean keyDown = Mods.on("freelook") && client.currentScreen == null
				&& (isRawKeyDown(Mods.keyCode("freelook", "key")) || OriginKeyBindings.freelook.isKeyDown());
		boolean freelookDown = Mods.bool("freelook", "toggle")
				? (keyDown && !freelookWasDown) != OriginFreelookState.active
				: keyDown;
		if (freelookDown && !OriginFreelookState.active) {
			OriginFreelookState.cameraYaw = player.rotationYaw;
			OriginFreelookState.cameraPitch = player.rotationPitch;
			OriginFreelookState.active = true;
			// Third Person mode = Lunar-style orbit: jump to third person for
			// the hold and restore whatever perspective the player was in.
			// 1.8.9: thirdPersonView 0 = first person, 1 = behind, 2 = in front.
			if (Mods.mode("freelook", "listMode").equals("Third Person")) {
				savedPerspective = client.gameSettings.thirdPersonView;
				client.gameSettings.thirdPersonView = 1;
			}
		} else if (!freelookDown && OriginFreelookState.active) {
			OriginFreelookState.active = false;
			if (savedPerspective >= 0) {
				client.gameSettings.thirdPersonView = savedPerspective;
				savedPerspective = -1;
			}
		}
		freelookWasDown = keyDown;

		// Smooth Camera Movement (zoom / freelook / snaplook): ride vanilla's cinematic
		// camera smoothing while either is engaged, then restore the player's own
		// setting the moment both release.
		boolean wantSmooth = (zoomActive && Mods.bool("zoom", "smoothCamera"))
				|| (OriginFreelookState.active && Mods.on("freelook") && Mods.bool("freelook", "smoothCamera"))
				|| snaplookWantsSmooth();
		if (wantSmooth && !smoothCamApplied) {
			savedSmoothCam = client.gameSettings.smoothCamera;
			client.gameSettings.smoothCamera = true;
			smoothCamApplied = true;
		} else if (!wantSmooth && smoothCamApplied) {
			client.gameSettings.smoothCamera = savedSmoothCam;
			smoothCamApplied = false;
		}
	}

	// Snaplook (world area) asks for cinematic smoothing while looking back.
	// Latched fail-soft: the world area may not have landed (or may throw).
	private boolean snaplookBroken = false;

	private boolean snaplookWantsSmooth() {
		if (snaplookBroken) {
			return false;
		}
		try {
			return com.origin.client.client.mods.Snaplook.wantsSmoothCamera();
		} catch (Throwable t) {
			snaplookBroken = true;
			return false;
		}
	}

	// Tab list — Origin drives visibility itself so it shows even on a solo local
	// server (vanilla refuses to). Holding the Player List key always shows it;
	// the Tab Editor mod adds sticky tap-to-lock + two-way keybind sync on top.
	// 1.8.9: both sides are LWJGL2 codes here; unbound is -1 on Origin's side and
	// KEY_NONE (0) on Minecraft's, so they are compared through tabCode().
	private void tickTabList(Minecraft client) {
		KeyBinding list = client.gameSettings.keyBindPlayerList;
		int mcTab = tabCode(list.getKeyCode());
		boolean rawTab = client.currentScreen == null && isRawKeyDown(mcTab);
		if (Mods.on("tablist")) {
			int ourTab = tabCode(Mods.keyCode("tablist", "tabKey"));
			if (!tabSyncInit) {
				// On first tick, adopt Minecraft's Player List binding as the source
				// of truth so the two always start in agreement.
				tabSyncInit = true;
				if (ourTab != mcTab) {
					Mods.set("tablist", "tabKey", mcTab);
					ourTab = mcTab;
				}
			} else if (ourTab != lastOurTabKey && ourTab != mcTab) {
				// Rebound in the Origin menu -> push to Minecraft's controls.
				client.gameSettings.setOptionKeyBinding(list, ourTab < 0 ? 0 : ourTab);
				KeyBinding.resetKeyBindingArrayAndHash();
				client.gameSettings.saveOptions();
				mcTab = ourTab;
			} else if (mcTab != lastMcTabKey && mcTab != ourTab) {
				// Rebound in Minecraft's controls -> pull into the Origin option.
				Mods.set("tablist", "tabKey", mcTab);
				ourTab = mcTab;
			}
			lastOurTabKey = ourTab;
			lastMcTabKey = mcTab;

			long now = System.currentTimeMillis();
			if (rawTab && !tabKeyWasDown) {
				tabPressAt = now;
			}
			if (!rawTab && tabKeyWasDown) {
				// Quick tap -> toggle the sticky lock; a longer hold-then-release just
				// ends the vanilla peek (sticky stays off).
				if (Mods.bool("tablist", "stickyToggle") && (now - tabPressAt) < 200) {
					tabStickyOn = !tabStickyOn;
				} else {
					tabStickyOn = false;
				}
			}
		} else {
			tabStickyOn = false;
			tabSyncInit = false;
		}
		tabKeyWasDown = rawTab;
		// Held -> always visible (even solo / mod off); sticky lock only with the mod on.
		tabListVisible = rawTab || (Mods.on("tablist") && tabStickyOn);
	}

	private static int tabCode(int lwjgl) {
		return lwjgl == 0 ? -1 : lwjgl;
	}

	// 1.16.5's displayClientMessage(text, true): the action bar
	// (GuiIngame.setRecordPlaying with isPlaying=false, what S02 type 2 uses).
	private static void actionBar(Minecraft client, String text) {
		try {
			client.ingameGUI.setRecordPlaying(new ChatComponentText(text), false);
		} catch (Throwable t) {
			// message only
		}
	}

	// Weather Changer: the visible weather is produced on READ by the world
	// area's weather hook (WeatherOverride) -- nothing here mutates real world
	// state. What stays here is Play Thunder Sounds: forcing the thunder level
	// alone never makes a sound (vanilla thunder rides real lightning bolts we
	// don't spawn), so play a rolling thunder clip on a gentle cadence instead.
	public void applyWeather(Minecraft client) {
		if (!Mods.on("weather") || client.theWorld == null) {
			return;
		}
		String mode = Mods.mode("weather", "mode");
		boolean thunder = mode.equals("Thunder") || Mods.bool("weather", "thunder");
		if (thunder && Mods.bool("weather", "playThunderSounds") && client.thePlayer != null) {
			if (++thunderSoundTicks >= 300) {   // ~every 15 seconds
				thunderSoundTicks = 0;
				// 1.8.9: sounds are named strings; this is vanilla's thunder event.
				client.theWorld.playSound(client.thePlayer.posX, client.thePlayer.posY, client.thePlayer.posZ,
						"ambient.weather.thunder", 1.0f, 0.9f, false);
			}
		} else {
			thunderSoundTicks = 0;
		}
	}

	// Time Changer: slider (live), Use Real Current Time, +/- keybinds, and
	// Time Passage all resolve into one timeOverride that the world area reads.
	private void applyTimeChanger(Minecraft client) {
		if (!Mods.on("timechanger")) {
			return;
		}
		double base = Mods.num("timechanger", "time");
		if (Mods.bool("timechanger", "useRealTime")) {
			java.util.Calendar now = java.util.Calendar.getInstance();
			// noon IRL = noon in-game: 06:00 IRL maps to tick 0 (sunrise)
			base = ((now.get(java.util.Calendar.HOUR_OF_DAY) * 1000.0
					+ now.get(java.util.Calendar.MINUTE) * 1000.0 / 60.0) - 6000 + 24000) % 24000;
		}
		boolean inc = client.currentScreen == null && isRawKeyDown(Mods.keyCode("timechanger", "increaseKey"));
		boolean dec = client.currentScreen == null && isRawKeyDown(Mods.keyCode("timechanger", "decreaseKey"));
		if (inc && !timeIncWasDown) {
			base = (base + 500) % 24000;
			Mods.set("timechanger", "time", base);
		}
		if (dec && !timeDecWasDown) {
			base = (base - 500 + 24000) % 24000;
			Mods.set("timechanger", "time", base);
		}
		timeIncWasDown = inc;
		timeDecWasDown = dec;
		if (Mods.bool("timechanger", "timePassage")) {
			// speed 1.0 = vanilla day speed (+1 tick per tick), kept transient
			// so it never spams config saves
			timePassageAccum = (timePassageAccum + Mods.num("timechanger", "speed")) % 24000;
		} else {
			timePassageAccum = 0;
		}
		timeOverride = (base + timePassageAccum) % 24000;
	}

	// Live sky while dragging the Time slider in the mod menu. applyTimeChanger
	// only runs per client tick (20x/sec), so the sky visibly stepped behind the
	// thumb; the menu calls this on every drag event instead. Use Real Current
	// Time ignores the slider there, so it ignores it here too.
	public static void previewTimeSlider(double time) {
		if (Mods.bool("timechanger", "useRealTime")) {
			return;
		}
		timeOverride = (time + timePassageAccum) % 24000;
	}

	// Chat options. 1.8.9: Scale rides vanilla's chatScale (re-wrapping the chat
	// when it changes, as the vanilla slider does). Background Opacity has no
	// vanilla option to ride on 1.8.9 -- chatOpacity fades the TEXT too, the very
	// mapping 1.16.5 warns against -- so it is applied per line by
	// GuiNewChatMixin through chatBackground(); chatOpacity is never touched.
	private void applyChat(Minecraft client) {
		boolean on = Mods.on("chat");
		if (on) {
			if (!chatApplied) {
				savedChatScale = client.gameSettings.chatScale;
				chatApplied = true;
			}
			float scale = (float) Mods.num("chat", "scale");
			if (scale != appliedChatScale) {
				client.gameSettings.chatScale = scale;
				appliedChatScale = scale;
				refreshChat(client);
			}
		} else if (chatApplied) {
			client.gameSettings.chatScale = savedChatScale;
			chatApplied = false;
			appliedChatScale = -1f;
			refreshChat(client);
		}
	}

	private static void refreshChat(Minecraft client) {
		try {
			if (client.ingameGUI != null) {
				client.ingameGUI.getChatGUI().refreshChat();
			}
		} catch (Throwable t) {
			// lines re-wrap on the next message instead
		}
	}

	// Hitbox mod on = vanilla's F3+B boxes on (the world area restyles them).
	private void applyHitboxes(Minecraft client) {
		boolean on = Mods.on("hitboxes");
		if (on != hitboxesApplied) {
			client.getRenderManager().setDebugBoundingBox(on);
			hitboxesApplied = on;
		}
	}
}
