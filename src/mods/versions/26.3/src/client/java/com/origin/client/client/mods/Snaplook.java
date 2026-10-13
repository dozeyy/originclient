package com.origin.client.client.mods;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.OriginFreelookState;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * Snaplook (Lunar parity): hold the key to look behind you -- the camera flips to
 * the front-facing third person view (what F5 shows second) and snaps back to
 * whatever view you had the moment you let go. Toggle Mode makes it press-on /
 * press-off. Smooth Camera Movement rides vanilla's cinematic camera while it's
 * engaged (OriginClientMod folds wantsSmoothCamera() into the same save/restore
 * it uses for Zoom and Freelook, so the player's own setting always comes back).
 *
 * Ticked from OriginClientMod.onEndTick. Freelook wins if both are pressed: it
 * already owns the camera, and two features swapping perspective at once would
 * fight over what to restore.
 */
public final class Snaplook {
	private static final String MOD = "snaplook";

	private static boolean active = false;
	private static boolean keyWasDown = false;
	private static CameraType saved = null;

	private Snaplook() {
	}

	public static void tick(Minecraft client) {
		boolean enabled = Mods.on(MOD);
		boolean keyDown = enabled && client.gui.screen() == null && client.player != null
				&& !OriginFreelookState.active
				&& OriginClientMod.isRawKeyDown(Mods.keyCode(MOD, "key"));
		boolean want;
		if (!enabled || client.player == null) {
			want = false;   // turning the mod off (or leaving the world) always releases it
		} else if (Mods.bool(MOD, "toggleMode")) {
			want = (keyDown && !keyWasDown) != active;
		} else {
			want = keyDown;
		}
		if (want && !active) {
			saved = client.options.getCameraType();
			client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
			active = true;
		} else if (!want && active) {
			active = false;
			if (saved != null) {
				client.options.setCameraType(saved);
				saved = null;
			}
		}
		keyWasDown = keyDown;
	}

	public static boolean wantsSmoothCamera() {
		return active && Mods.bool(MOD, "smoothCamera");
	}
}
