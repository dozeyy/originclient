package com.origin.client.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public class OriginClientMod implements ClientModInitializer {
	public static final OriginFeatures FEATURES = OriginConfig.load();

	private boolean freelookWasDown = false;
	private boolean fullbrightApplied = false;
	private double savedGamma = 1.0;

	@Override
	public void onInitializeClient() {
		OriginKeyBindings.register();

		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
		HudRenderCallback.EVENT.register((guiGraphics, tickCounter) -> OriginHud.render(guiGraphics));
	}

	private void onEndTick(Minecraft client) {
		applyFullbright(client);

		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}

		if (FEATURES.toggleSprintEnabled) {
			if (client.options.keySprint.consumeClick()) {
				FEATURES.sprintToggledOn = !FEATURES.sprintToggledOn;
			}
			player.setSprinting(FEATURES.sprintToggledOn && player.isAlive());
		}

		if (FEATURES.toggleSneakEnabled) {
			if (client.options.keyShift.consumeClick()) {
				FEATURES.sneakToggledOn = !FEATURES.sneakToggledOn;
			}
			player.setShiftKeyDown(FEATURES.sneakToggledOn);
		}

		boolean freelookDown = FEATURES.freelookEnabled && OriginKeyBindings.freelook.isDown();
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

	// Best-effort first pass: pushes the vanilla gamma option far past its
	// normal 0-1 slider range. If this version's OptionInstance validator
	// clamps writes back to 1.0, fullbright will cap at vanilla's max
	// brightness rather than true full white — needs an in-game check.
	private void applyFullbright(Minecraft client) {
		if (FEATURES.fullbrightEnabled && !fullbrightApplied) {
			savedGamma = client.options.gamma().get();
			client.options.gamma().set(16.0);
			fullbrightApplied = true;
		} else if (!FEATURES.fullbrightEnabled && fullbrightApplied) {
			client.options.gamma().set(savedGamma);
			fullbrightApplied = false;
		}
	}
}
