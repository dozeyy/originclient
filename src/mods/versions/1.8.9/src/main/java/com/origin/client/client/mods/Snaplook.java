package com.origin.client.client.mods;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.OriginFreelookState;
import net.minecraft.client.Minecraft;

/**
 * Snaplook (Lunar parity): hold the key to look behind you -- the camera flips to
 * the front-facing third person view (what F5 shows second) and snaps back to
 * whatever view you had the moment you let go. Toggle Mode makes it press-on /
 * press-off. Smooth Camera Movement rides vanilla's cinematic camera while it's
 * engaged (OriginClientMod folds wantsSmoothCamera() into the same save/restore
 * it uses for Zoom and Freelook, so the player's own setting always comes back).
 *
 * Ticked from OriginClientMod's client tick. Freelook wins if both are pressed:
 * it already owns the camera, and two features swapping perspective at once
 * would fight over what to restore.
 *
 * 1.8.9: the camera type is GameSettings.thirdPersonView (0 first person,
 * 1 third person back, 2 third person front) -- there is no CameraType enum.
 */
public final class Snaplook {
	private static final String MOD = "snaplook";
	private static final int THIRD_PERSON_FRONT = 2;

	private static boolean active = false;
	private static boolean keyWasDown = false;
	private static int saved = -1;

	private Snaplook() {
	}

	public static void tick(Minecraft client) {
		boolean enabled = Mods.on(MOD);
		boolean keyDown = enabled && client.currentScreen == null && client.thePlayer != null
				&& !OriginFreelookState.active
				&& OriginClientMod.isRawKeyDown(Mods.keyCode(MOD, "key"));
		boolean want;
		if (!enabled || client.thePlayer == null) {
			want = false;   // turning the mod off (or leaving the world) always releases it
		} else if (Mods.bool(MOD, "toggleMode")) {
			want = (keyDown && !keyWasDown) != active;
		} else {
			want = keyDown;
		}
		if (want && !active) {
			saved = client.gameSettings.thirdPersonView;
			client.gameSettings.thirdPersonView = THIRD_PERSON_FRONT;
			active = true;
		} else if (!want && active) {
			active = false;
			if (saved >= 0) {
				client.gameSettings.thirdPersonView = saved;
				saved = -1;
			}
		}
		keyWasDown = keyDown;
	}

	public static boolean wantsSmoothCamera() {
		return active && Mods.bool(MOD, "smoothCamera");
	}
}
