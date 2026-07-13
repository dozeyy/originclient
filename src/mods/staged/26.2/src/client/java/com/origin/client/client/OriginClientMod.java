package com.origin.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.gui.OriginModMenuScreen;
import com.origin.client.client.mods.Mods;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
// 26.2 core-first: world-render mods (ChunkBorderRenderer/BlockOverlayRenderer/
// MotionBlur) + the shader-browser UI (OriginShaderButton/ShaderBrowserScreen) are
// STUBBED — moved to disabled262/ pending a rework onto 26.2's new line/gizmo
// pipeline. WorldRenderEvents was replaced by LevelRenderEvents (see PORT-262.md).
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

// Client entrypoint + per-tick feature hub. All feature state lives in the
// Mods registry (originclient-mods.json); the legacy FEATURES object stays
// only as the migration source and for its transient sprint/sneak state.
public class OriginClientMod implements ClientModInitializer {
	public static final OriginFeatures FEATURES = OriginConfig.load();

	private boolean freelookWasDown = false;
	private net.minecraft.client.CameraType savedPerspective = null;
	private boolean sneakForced = false;
	private boolean chatApplied = false;
	private double savedChatOpacity = 1.0, savedChatScale = 1.0;
	private boolean hitboxesApplied = false;
	private boolean chunkKeyWasDown = false;
	private boolean sprintKeyWasDown = false, sneakKeyWasDown = false;
	private boolean scoreboardKeyWasDown = false;
	private boolean fullbrightKeyWasDown = false;
	private boolean zoomKeyWasDown = false;
	private boolean timeIncWasDown = false, timeDecWasDown = false;
	private double timePassageAccum = 0;

	// Live time-changer output, read by LevelTimeMixin every frame.
	public static volatile double timeOverride = 6000;
	// Toggle-mode zoom latch. zoomActive = zoom is engaged this tick (the target
	// GameRendererMixin eases the FOV toward, frame-side). zoomScrollFactor is a
	// transient scroll-to-zoom multiplier, reset whenever zoom disengages.
	public static volatile boolean zoomToggled = false;
	public static volatile boolean zoomActive = false;
	public static volatile double zoomScrollFactor = 1.0;
	// Nametag toggle latches, read by EntityNametagMixin.
	public static volatile boolean nametagsHidden = false, playerNametagsHidden = false;
	private boolean nametagAllKeyWasDown = false, nametagPlayerKeyWasDown = false;
	// Zoomed Sensitivity + Smooth Camera (zoom/freelook) save-and-restore state.
	private boolean zoomSensApplied = false;
	private double savedSensitivity = 0.5;
	private boolean smoothCamApplied = false;
	private boolean savedSmoothCam = false;
	// Fly Boost restore latch + thunder-sound cadence counter.
	private boolean flyBoostApplied = false;
	private int thunderSoundTicks = 0;

	@Override
	public void onInitializeClient() {
		// Load the mod config + run the one-time legacy migration eagerly on the
		// init thread, so the first HUD frame (render thread) reads a fully-loaded,
		// thread-safe store instead of racing the lazy load — otherwise enabled
		// HUD mods can flicker off until the player toggles them.
		Mods.preload();
		OriginKeyBindings.register();
		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
		// Single authoritative autosave when the player leaves the game (quit to
		// desktop / window close). Mod + HUD settings already save eagerly on
		// every change; this flushes all three stores once on exit so nothing is
		// ever lost — including vanilla game options, which the mod otherwise
		// never writes. Per-version isolation is automatic: options.txt and the
		// config/ dir both live inside this version's own instance folder.
		ClientLifecycleEvents.CLIENT_STOPPING.register(this::onClientStopping);
		// Origin's HUD (FPS/CPS/coords/armor/keystrokes/potions/etc.) draws as a
		// Fabric HUD element registered LAST, so it lands on top of the whole
		// vanilla HUD and any other mod's HUD. 26.2 removed the old lever (the
		// Gui.render RETURN mixin — Gui.extractRenderState no longer carries a
		// GuiGraphicsExtractor); HudElementRegistry.addLast is the proper 26.2
		// "draw after everything" layer and hands us the extractor directly.
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath("originclient", "hud"),
				(guiGraphics, deltaTracker) -> OriginHud.extractRenderState(guiGraphics));

		// Scoreboard mod: rescale the vanilla sidebar around its right-center
		// anchor, or hide it entirely. 26.2 removed the Gui.displayScoreboardSidebar
		// draw hook (immediate-mode GuiGraphics is gone), so we wrap the vanilla
		// SCOREBOARD hud element instead — same effect, proper 26.2 lever.
		HudElementRegistry.replaceElement(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.SCOREBOARD,
				original -> (g, delta) -> {
					if (Mods.on("scoreboard") && Mods.bool("scoreboard", "hideScoreboard")) {
						return;
					}
					if (Mods.on("scoreboard")) {
						float s = (float) Mods.num("scoreboard", "scale");
						if (s <= 0.05f) {
							s = 1f;
						}
						float px = g.guiWidth();
						float py = g.guiHeight() / 2f;
						var pose = g.pose();
						pose.pushMatrix();
						pose.translate(px, py);
						pose.scale(s, s);
						pose.translate(-px, -py);
						original.extractRenderState(g, delta);
						pose.popMatrix();
					} else {
						original.extractRenderState(g, delta);
					}
				});

		// Potion Effects mod: the "Vanilla Display" toggle decides whether the
		// default top-right effect icons still show alongside Origin's own movable
		// element. Wrap the vanilla MOB_EFFECTS element (26.2 removed Gui.renderEffects).
		HudElementRegistry.replaceElement(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.MOB_EFFECTS,
				original -> (g, delta) -> {
					if (Mods.on("potionhud") && !Mods.bool("potionhud", "vanillaDisplay")) {
						return;
					}
					original.extractRenderState(g, delta);
				});

		// World-space overlays (Chunk Borders + Hitboxes) via 26.2's submit/gizmo
		// pipeline. Block Outline restyle is a LevelRenderer mixin (its color/width
		// are draw args); these two draw their own line geometry each frame.
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(
				com.origin.client.client.render.OriginWorldRender::collect);

		// 26.2 core-first STUB: the shader-download button injection, the chunk-
		// border + block-overlay world renderers (WorldRenderEvents → LevelRenderEvents,
		// RenderType.lines/MultiBufferSource gone, block-outline is now a render-state
		// extraction), and the potion "show in inventory" (ScreenEvents.afterRender →
		// afterExtract) all need a rework onto 26.2's new pipeline. Re-add each once
		// its renderer is ported back from disabled262/. See PORT-262.md.
	}

	// Flush everything to disk once, when Minecraft is shutting down. Each
	// store is saved in its own try so a failure in one never blocks the
	// others, and nothing here is allowed to throw into the shutdown path.
	private void onClientStopping(Minecraft client) {
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
			if (client.options != null) {
				client.options.save();        // vanilla game options -> options.txt
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: failed to save game options on exit", t);
		}
	}

	/** Raw GLFW key state for the mod-menu's rebindable keys. */
	public static boolean isRawKeyDown(int glfwKey) {
		if (glfwKey <= 0) {
			return false;
		}
		try {
			return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), glfwKey);
		} catch (Throwable t) {
			return false;
		}
	}

	private void onEndTick(Minecraft client) {
		// Right Shift opens the mod menu (the screen itself closes on the
		// same key or Esc, with the reversed slide).
		// Right Shift opens the quick HUD-edit screen (drag/resize elements
		// directly, ORIGIN logo, dark MODS button into the full grid).
		while (OriginKeyBindings.openModMenu.consumeClick()) {
			if (Minecraft.getInstance().mouseHandler.isMouseGrabbed()) {
				client.setScreenAndShow(new com.origin.client.client.hud.HudEditorScreen(true));
			}
		}

		// Copy Coords To Clipboard: press the (unbound-by-default) key to drop
		// your XYZ on the clipboard, gated by the Coordinates mod + its toggle.
		while (OriginKeyBindings.copyCoords.consumeClick()) {
			LocalPlayer p = client.player;
			if (p != null && Mods.on("coords") && Mods.bool("coords", "copyClipboard")) {
				String c = p.blockPosition().getX() + ", " + p.blockPosition().getY() + ", " + p.blockPosition().getZ();
				client.keyboardHandler.setClipboard(c);
				p.sendSystemMessage(net.minecraft.network.chat.Component.literal("Copied coordinates: " + c));
			}
		}

		applyChat(client);
		applyHitboxes(client);
		applyWeather(client);
		applyTimeChanger(client);
		GeneralSettings.tick(client);
		// MotionBlur.tick(client); // 26.2 STUB — MotionBlur moved to disabled262/

		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}

		// Scoreboard hide toggle keybind (+ optional action-bar message)
		boolean sbDown = Minecraft.getInstance().mouseHandler.isMouseGrabbed() && isRawKeyDown(Mods.keyCode("scoreboard", "toggleKey"));
		if (sbDown && !scoreboardKeyWasDown) {
			boolean hidden = !Mods.bool("scoreboard", "hideScoreboard");
			Mods.set("scoreboard", "hideScoreboard", hidden);
			if (Mods.bool("scoreboard", "displayToggleMessage")) {
				player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
						hidden ? "Scoreboard hidden" : "Scoreboard shown"));
			}
		}
		scoreboardKeyWasDown = sbDown;

		// Full Bright toggle keybind flips the mod's own sub-toggle
		boolean fbDown = Minecraft.getInstance().mouseHandler.isMouseGrabbed() && isRawKeyDown(Mods.keyCode("fullbright", "key"));
		if (fbDown && !fullbrightKeyWasDown) {
			Mods.set("fullbright", "fullBright", !Mods.bool("fullbright", "fullBright"));
		}
		fullbrightKeyWasDown = fbDown;

		// Nametag toggle keybinds (default unbound). Toggle All hides every
		// nametag; Toggle Players hides only other players'.
		boolean naDown = Minecraft.getInstance().mouseHandler.isMouseGrabbed() && isRawKeyDown(Mods.keyCode("nametags", "toggleAll"));
		if (naDown && !nametagAllKeyWasDown) {
			nametagsHidden = !nametagsHidden;
			if (Mods.bool("nametags", "displayToggleMessage")) {
				player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
						nametagsHidden ? "Nametags hidden" : "Nametags shown"));
			}
		}
		nametagAllKeyWasDown = naDown;

		boolean npDown = Minecraft.getInstance().mouseHandler.isMouseGrabbed() && isRawKeyDown(Mods.keyCode("nametags", "togglePlayers"));
		if (npDown && !nametagPlayerKeyWasDown) {
			playerNametagsHidden = !playerNametagsHidden;
			if (Mods.bool("nametags", "displayToggleMessage")) {
				player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
						playerNametagsHidden ? "Player nametags hidden" : "Player nametags shown"));
			}
		}
		nametagPlayerKeyWasDown = npDown;

		// Toggle Zoom: in toggle mode the key latches instead of holding. The
		// FOV easing itself lives in GameRendererMixin (frame-side); here we only
		// resolve whether zoom is engaged and expose it as zoomActive.
		boolean zDown = Minecraft.getInstance().mouseHandler.isMouseGrabbed()
				&& (isRawKeyDown(Mods.keyCode("zoom", "key")) || OriginKeyBindings.zoom.isDown());
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
				savedSensitivity = client.options.sensitivity().get();
				zoomSensApplied = true;
			}
			double f = Mods.num("zoom", "sensitivity");
			client.options.sensitivity().set(Math.max(0.0, Math.min(1.0, savedSensitivity * (f <= 0 ? 1.0 : f))));
		} else if (zoomSensApplied) {
			client.options.sensitivity().set(savedSensitivity);
			zoomSensApplied = false;
		}

		// Chunk borders toggle keybind (edge-triggered, ignored in screens).
		int cbKey = Mods.keyCode("chunkborders", "key");
		boolean cbDown = Minecraft.getInstance().mouseHandler.isMouseGrabbed() && isRawKeyDown(cbKey);
		if (cbDown && !chunkKeyWasDown) {
			Mods.setOn("chunkborders", !Mods.on("chunkborders"));
		}
		chunkKeyWasDown = cbDown;

		// Toggle Sprint / Sneak. "Hold" mode = vanilla behavior (we do
		// nothing); "Toggle" flips on the vanilla key or the custom bind.
		// Toggle Sneak/Sprint is one mod now (spec §5): the "sprint"/"sneak"
		// sub-toggles pick which the hands-free behavior applies to.
		// Toggle happens ONLY on the mod's assigned toggle key — we no longer
		// consume the vanilla sprint/sneak keys, so double-tap-W sprinting and
		// hold-Ctrl / hold-Shift keep working normally. We also never force the
		// state OFF: sprint is only pushed ON while toggled+moving, and sneak is
		// released once on un-toggle, so vanilla stays in control the rest of the
		// time.
		boolean toggleMod = Mods.on("togglesprint");
		if (toggleMod && Mods.bool("togglesprint", "sprint")) {
			boolean custom = Minecraft.getInstance().mouseHandler.isMouseGrabbed() && isRawKeyDown(Mods.keyCode("togglesprint", "key"));
			boolean edge = custom && !sprintKeyWasDown;
			sprintKeyWasDown = custom;
			if (edge) {
				FEATURES.sprintToggledOn = !FEATURES.sprintToggledOn;
			}
			boolean moving = player.input != null && player.input.hasForwardImpulse();
			if (FEATURES.sprintToggledOn && player.isAlive() && moving) {
				player.setSprinting(true);
			}
		} else {
			FEATURES.sprintToggledOn = false;
		}

		if (toggleMod && Mods.bool("togglesprint", "sneak")) {
			boolean custom = Minecraft.getInstance().mouseHandler.isMouseGrabbed() && isRawKeyDown(Mods.keyCode("togglesprint", "key"));
			boolean edge = custom && !sneakKeyWasDown;
			sneakKeyWasDown = custom;
			if (edge) {
				FEATURES.sneakToggledOn = !FEATURES.sneakToggledOn;
			}
			if (FEATURES.sneakToggledOn) {
				player.setShiftKeyDown(true);
				sneakForced = true;
			} else if (sneakForced) {
				player.setShiftKeyDown(false);   // one-shot release; hold-Shift then works normally
				sneakForced = false;
			}
		} else {
			if (sneakForced) {
				player.setShiftKeyDown(false);
				sneakForced = false;
			}
			FEATURES.sneakToggledOn = false;
		}

		// Fly Boost: multiply flight speed, but ONLY in a mode that actually
		// allows flight (creative/spectator) and only while airborne — it never
		// touches survival movement, so it's a QoL tweak, not a speed hack.
		if (Mods.on("togglesprint") && Mods.bool("togglesprint", "flyBoost")
				&& player.getAbilities().flying && (player.isCreative() || player.isSpectator())) {
			float mult = (float) Math.max(1.0, Mods.num("togglesprint", "flyBoostAmount"));
			player.getAbilities().setFlyingSpeed(0.05f * mult);
			flyBoostApplied = true;
		} else if (flyBoostApplied) {
			player.getAbilities().setFlyingSpeed(0.05f);
			flyBoostApplied = false;
		}

		// Freelook: custom bind (default Left Alt) with the vanilla-controls
		// binding as a fallback. Hold = look around; release = snap back (the
		// player's real rotation is never touched — see MouseHandlerMixin).
		// "Toggle Freelook" turns the hold into a press-on/press-off latch.
		boolean keyDown = Mods.on("freelook") && Minecraft.getInstance().mouseHandler.isMouseGrabbed()
				&& (isRawKeyDown(Mods.keyCode("freelook", "key")) || OriginKeyBindings.freelook.isDown());
		boolean freelookDown = Mods.bool("freelook", "toggle")
				? (keyDown && !freelookWasDown) != OriginFreelookState.active
				: keyDown;
		if (freelookDown && !OriginFreelookState.active) {
			OriginFreelookState.cameraYaw = player.getViewYRot(1f);
			OriginFreelookState.cameraPitch = player.getViewXRot(1f);
			OriginFreelookState.active = true;
			// Third Person mode = Lunar-style orbit: jump to third person for
			// the hold and restore whatever perspective the player was in.
			if (Mods.mode("freelook", "listMode").equals("Third Person")) {
				savedPerspective = client.options.getCameraType();
				client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			}
		} else if (!freelookDown && OriginFreelookState.active) {
			OriginFreelookState.active = false;
			if (savedPerspective != null) {
				client.options.setCameraType(savedPerspective);
				savedPerspective = null;
			}
		}
		freelookWasDown = keyDown;

		// Smooth Camera Movement (zoom / freelook): ride vanilla's cinematic
		// camera smoothing while either is engaged, then restore the player's own
		// setting the moment both release.
		boolean wantSmooth = (zoomActive && Mods.bool("zoom", "smoothCamera"))
				|| (OriginFreelookState.active && Mods.on("freelook") && Mods.bool("freelook", "smoothCamera"));
		if (wantSmooth && !smoothCamApplied) {
			savedSmoothCam = client.options.smoothCamera;
			client.options.smoothCamera = true;
			smoothCamApplied = true;
		} else if (!wantSmooth && smoothCamApplied) {
			client.options.smoothCamera = savedSmoothCam;
			smoothCamApplied = false;
		}
	}

	// Weather Changer: forces client-side rain/thunder levels per the mode.
	// Snow mode renders as snowfall in cold biomes (that's how MC does snow);
	// Clear additionally skips the precipitation render pass entirely
	// (LevelRendererMixin). Server weather/gameplay is never touched.
	private void applyWeather(Minecraft client) {
		if (!Mods.on("weather") || client.level == null) {
			return;
		}
		String mode = Mods.mode("weather", "mode");
		client.level.setRainLevel(mode.equals("Clear") ? 0f : 1f);
		boolean thunder = mode.equals("Thunder") || Mods.bool("weather", "thunder");
		client.level.setThunderLevel(thunder ? 1f : 0f);

		// Play Thunder Sounds: forcing the thunder level alone never actually
		// makes a sound (vanilla thunder rides real lightning bolts we don't
		// spawn), so play a rolling thunder clip on a gentle cadence instead.
		if (thunder && Mods.bool("weather", "playThunderSounds") && client.player != null) {
			if (++thunderSoundTicks >= 300) {   // ~every 15 seconds
				thunderSoundTicks = 0;
				client.level.playLocalSound(client.player.getX(), client.player.getY(), client.player.getZ(),
						net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_THUNDER,
						net.minecraft.sounds.SoundSource.WEATHER, 1.0f, 0.9f, false);
			}
		} else {
			thunderSoundTicks = 0;
		}
	}

	// Time Changer: slider (live), Use Real Current Time, +/- keybinds, and
	// Time Passage all resolve into one timeOverride that LevelTimeMixin reads.
	private void applyTimeChanger(Minecraft client) {
		if (!Mods.on("timechanger")) {
			return;
		}
		double base = Mods.num("timechanger", "time");
		if (Mods.bool("timechanger", "useRealTime")) {
			var now = java.time.LocalTime.now();
			// noon IRL = noon in-game: 06:00 IRL maps to tick 0 (sunrise)
			base = ((now.getHour() * 1000.0 + now.getMinute() * 1000.0 / 60.0) - 6000 + 24000) % 24000;
		}
		boolean inc = Minecraft.getInstance().mouseHandler.isMouseGrabbed() && isRawKeyDown(Mods.keyCode("timechanger", "increaseKey"));
		boolean dec = Minecraft.getInstance().mouseHandler.isMouseGrabbed() && isRawKeyDown(Mods.keyCode("timechanger", "decreaseKey"));
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

	// Chat options ride the vanilla accessibility options. IMPORTANT mapping:
	// "Background Opacity" drives textBackgroundOpacity — the grey box behind
	// chat lines — NOT chatOpacity (which fades the TEXT itself; wiring the
	// slider there made messages unreadable). Text stays fully visible at
	// background 0. chatOpacity is pinned to 1.0 while the mod is on, which
	// also heals any options.txt damage the old wiring left behind.
	private void applyChat(Minecraft client) {
		boolean on = Mods.on("chat");
		if (on) {
			if (!chatApplied) {
				savedChatOpacity = client.options.textBackgroundOpacity().get();
				savedChatScale = client.options.chatScale().get();
				chatApplied = true;
			}
			client.options.textBackgroundOpacity().set(Mods.num("chat", "opacity"));
			client.options.chatOpacity().set(1.0);
			client.options.chatScale().set(Mods.num("chat", "scale"));
		} else if (chatApplied) {
			client.options.textBackgroundOpacity().set(savedChatOpacity);
			client.options.chatScale().set(savedChatScale);
			chatApplied = false;
		}
	}

	private void applyHitboxes(Minecraft client) {
		boolean on = Mods.on("hitboxes");
		if (on != hitboxesApplied) {
			// 26.2 STUB: EntityRenderDispatcher.setRenderHitBoxes removed. // client.getEntityRenderDispatcher().setRenderHitBoxes(on);
			hitboxesApplied = on;
		}
	}
}
