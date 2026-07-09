package com.origin.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.gui.OriginModMenuScreen;
import com.origin.client.client.mods.ChunkBorderRenderer;
import com.origin.client.client.mods.MotionBlur;
import com.origin.client.client.mods.Mods;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

// Client entrypoint + per-tick feature hub. All feature state lives in the
// Mods registry (originclient-mods.json); the legacy FEATURES object stays
// only as the migration source and for its transient sprint/sneak state.
public class OriginClientMod implements ClientModInitializer {
	public static final OriginFeatures FEATURES = OriginConfig.load();

	private boolean freelookWasDown = false;
	private net.minecraft.client.CameraType savedPerspective = null;
	private boolean gammaApplied = false;
	private double savedGamma = 1.0;
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
	// Toggle-mode zoom latch + smooth-zoom progress (0..1), read by GameRendererMixin.
	public static volatile boolean zoomToggled = false;
	public static volatile double zoomProgress = 0;
	// Nametag toggle latches, read by EntityNametagMixin.
	public static volatile boolean nametagsHidden = false, playerNametagsHidden = false;
	private boolean nametagAllKeyWasDown = false, nametagPlayerKeyWasDown = false;

	@Override
	public void onInitializeClient() {
		OriginKeyBindings.register();
		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
		HudRenderCallback.EVENT.register((guiGraphics, tickCounter) ->
				com.origin.client.client.hud.HudElements.renderAll(guiGraphics));
		WorldRenderEvents.LAST.register(context -> {
			try {
				ChunkBorderRenderer.render(context);
			} catch (Throwable t) {
				// overlay must never take the frame down
			}
		});
	}

	/** Raw GLFW key state for the mod-menu's rebindable keys. */
	public static boolean isRawKeyDown(int glfwKey) {
		if (glfwKey <= 0) {
			return false;
		}
		try {
			return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), glfwKey);
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
			if (client.screen == null) {
				client.setScreen(new com.origin.client.client.hud.HudEditorScreen(true));
			}
		}

		applyFullbright(client);
		applyChat(client);
		applyHitboxes(client);
		applyWeather(client);
		applyTimeChanger(client);
		MotionBlur.tick(client);

		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}

		// Scoreboard hide toggle keybind (+ optional action-bar message)
		boolean sbDown = client.screen == null && isRawKeyDown(Mods.keyCode("scoreboard", "toggleKey"));
		if (sbDown && !scoreboardKeyWasDown) {
			boolean hidden = !Mods.bool("scoreboard", "hideScoreboard");
			Mods.set("scoreboard", "hideScoreboard", hidden);
			if (Mods.bool("scoreboard", "displayToggleMessage")) {
				player.displayClientMessage(net.minecraft.network.chat.Component.literal(
						hidden ? "Scoreboard hidden" : "Scoreboard shown"), true);
			}
		}
		scoreboardKeyWasDown = sbDown;

		// Full Bright toggle keybind flips the mod's own sub-toggle
		boolean fbDown = client.screen == null && isRawKeyDown(Mods.keyCode("fullbright", "key"));
		if (fbDown && !fullbrightKeyWasDown) {
			Mods.set("fullbright", "fullBright", !Mods.bool("fullbright", "fullBright"));
		}
		fullbrightKeyWasDown = fbDown;

		// Nametag toggle keybinds (default unbound). Toggle All hides every
		// nametag; Toggle Players hides only other players'.
		boolean naDown = client.screen == null && isRawKeyDown(Mods.keyCode("nametags", "toggleAll"));
		if (naDown && !nametagAllKeyWasDown) {
			nametagsHidden = !nametagsHidden;
			if (Mods.bool("nametags", "displayToggleMessage")) {
				player.displayClientMessage(net.minecraft.network.chat.Component.literal(
						nametagsHidden ? "Nametags hidden" : "Nametags shown"), true);
			}
		}
		nametagAllKeyWasDown = naDown;

		boolean npDown = client.screen == null && isRawKeyDown(Mods.keyCode("nametags", "togglePlayers"));
		if (npDown && !nametagPlayerKeyWasDown) {
			playerNametagsHidden = !playerNametagsHidden;
			if (Mods.bool("nametags", "displayToggleMessage")) {
				player.displayClientMessage(net.minecraft.network.chat.Component.literal(
						playerNametagsHidden ? "Player nametags hidden" : "Player nametags shown"), true);
			}
		}
		nametagPlayerKeyWasDown = npDown;

		// Toggle Zoom: in toggle mode the key latches instead of holding
		boolean zDown = client.screen == null
				&& (isRawKeyDown(Mods.keyCode("zoom", "key")) || OriginKeyBindings.zoom.isDown());
		if (Mods.on("zoom") && Mods.bool("zoom", "toggleZoom")) {
			if (zDown && !zoomKeyWasDown) {
				zoomToggled = !zoomToggled;
			}
		} else {
			zoomToggled = false;
		}
		zoomKeyWasDown = zDown;
		// smooth-zoom easing: progress eases toward the active state, or snaps
		// when Smooth Zoom is off. GameRendererMixin lerps the FOV by this.
		boolean zoomActive = Mods.on("zoom") && (Mods.bool("zoom", "toggleZoom") ? zoomToggled : zDown);
		double zTarget = zoomActive ? 1 : 0;
		if (Mods.bool("zoom", "smoothZoom")) {
			zoomProgress += (zTarget - zoomProgress) * 0.4;
			if (Math.abs(zTarget - zoomProgress) < 0.001) {
				zoomProgress = zTarget;
			}
		} else {
			zoomProgress = zTarget;
		}

		// Chunk borders toggle keybind (edge-triggered, ignored in screens).
		int cbKey = Mods.keyCode("chunkborders", "key");
		boolean cbDown = client.screen == null && isRawKeyDown(cbKey);
		if (cbDown && !chunkKeyWasDown) {
			Mods.setOn("chunkborders", !Mods.on("chunkborders"));
		}
		chunkKeyWasDown = cbDown;

		// Toggle Sprint / Sneak. "Hold" mode = vanilla behavior (we do
		// nothing); "Toggle" flips on the vanilla key or the custom bind.
		// Toggle Sneak/Sprint is one mod now (spec §5): the "sprint"/"sneak"
		// sub-toggles pick which the hands-free behavior applies to.
		boolean toggleMod = Mods.on("togglesprint");
		if (toggleMod && Mods.bool("togglesprint", "sprint")) {
			boolean custom = client.screen == null && isRawKeyDown(Mods.keyCode("togglesprint", "key"));
			boolean edge = custom && !sprintKeyWasDown;
			sprintKeyWasDown = custom;
			if (client.options.keySprint.consumeClick() || edge) {
				FEATURES.sprintToggledOn = !FEATURES.sprintToggledOn;
			}
			player.setSprinting(FEATURES.sprintToggledOn && player.isAlive());
		} else {
			FEATURES.sprintToggledOn = false;
		}

		if (toggleMod && Mods.bool("togglesprint", "sneak")) {
			boolean custom = client.screen == null && isRawKeyDown(Mods.keyCode("togglesprint", "key"));
			boolean edge = custom && !sneakKeyWasDown;
			sneakKeyWasDown = custom;
			if (client.options.keyShift.consumeClick() || edge) {
				FEATURES.sneakToggledOn = !FEATURES.sneakToggledOn;
			}
			player.setShiftKeyDown(FEATURES.sneakToggledOn);
		} else {
			FEATURES.sneakToggledOn = false;
		}

		// Freelook: custom bind (default Left Alt) with the vanilla-controls
		// binding as a fallback. Hold = look around; release = snap back (the
		// player's real rotation is never touched — see MouseHandlerMixin).
		// "Toggle Freelook" turns the hold into a press-on/press-off latch.
		boolean keyDown = Mods.on("freelook") && client.screen == null
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
		boolean inc = client.screen == null && isRawKeyDown(Mods.keyCode("timechanger", "increaseKey"));
		boolean dec = client.screen == null && isRawKeyDown(Mods.keyCode("timechanger", "decreaseKey"));
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

	// FullBright: pushes vanilla gamma to the configured level (validator
	// permitting — flagged for live check) and restores the player's own
	// value on disable. Gated on the mod switch AND its Full Bright sub-toggle.
	private void applyFullbright(Minecraft client) {
		boolean on = Mods.on("fullbright") && Mods.bool("fullbright", "fullBright");
		double target = Mods.num("fullbright", "gamma");
		if (on) {
			if (!gammaApplied) {
				savedGamma = client.options.gamma().get();
				gammaApplied = true;
			}
			if (client.options.gamma().get() != target) {
				client.options.gamma().set(target);
			}
		} else if (gammaApplied) {
			client.options.gamma().set(savedGamma);
			gammaApplied = false;
		}
	}

	// Chat opacity/scale ride the vanilla accessibility options — no reason
	// to re-implement chat rendering for values the game already exposes.
	private void applyChat(Minecraft client) {
		boolean on = Mods.on("chat");
		if (on) {
			if (!chatApplied) {
				savedChatOpacity = client.options.chatOpacity().get();
				savedChatScale = client.options.chatScale().get();
				chatApplied = true;
			}
			client.options.chatOpacity().set(Mods.num("chat", "opacity"));
			client.options.chatScale().set(Mods.num("chat", "scale"));
		} else if (chatApplied) {
			client.options.chatOpacity().set(savedChatOpacity);
			client.options.chatScale().set(savedChatScale);
			chatApplied = false;
		}
	}

	private void applyHitboxes(Minecraft client) {
		boolean on = Mods.on("hitboxes");
		if (on != hitboxesApplied) {
			client.getEntityRenderDispatcher().setRenderHitBoxes(on);
			hitboxesApplied = on;
		}
	}
}
