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
	private boolean gammaApplied = false;
	private double savedGamma = 1.0;
	private boolean chatApplied = false;
	private double savedChatOpacity = 1.0, savedChatScale = 1.0;
	private boolean hitboxesApplied = false;
	private boolean chunkKeyWasDown = false;
	private boolean sprintKeyWasDown = false, sneakKeyWasDown = false;

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
		while (OriginKeyBindings.openModMenu.consumeClick()) {
			if (client.screen == null) {
				client.setScreen(new OriginModMenuScreen());
			}
		}

		applyFullbright(client);
		applyChat(client);
		applyHitboxes(client);
		MotionBlur.tick(client);

		LocalPlayer player = client.player;
		if (player == null) {
			return;
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
		if (Mods.on("togglesprint") && Mods.mode("togglesprint", "mode").equals("Toggle")) {
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

		if (Mods.on("togglesneak") && Mods.mode("togglesneak", "mode").equals("Toggle")) {
			boolean custom = client.screen == null && isRawKeyDown(Mods.keyCode("togglesneak", "key"));
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
		// binding as a fallback.
		boolean freelookDown = Mods.on("freelook") && client.screen == null
				&& (isRawKeyDown(Mods.keyCode("freelook", "key")) || OriginKeyBindings.freelook.isDown());
		if (freelookDown && !freelookWasDown) {
			OriginFreelookState.cameraYaw = player.getYRot();
			OriginFreelookState.cameraPitch = player.getXRot();
			OriginFreelookState.active = true;
		} else if (!freelookDown && freelookWasDown) {
			player.setYRot(OriginFreelookState.cameraYaw);
			player.setXRot(OriginFreelookState.cameraPitch);
			OriginFreelookState.active = false;
		}
		freelookWasDown = freelookDown;
	}

	// FullBright: pushes vanilla gamma to the configured level (validator
	// permitting — flagged for live check) and restores the player's own
	// value on disable.
	private void applyFullbright(Minecraft client) {
		boolean on = Mods.on("fullbright");
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
