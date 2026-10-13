package com.origin.client.client.hud;

import com.origin.client.client.gui.Gfx;
import net.minecraft.client.Minecraft;

/**
 * Cooldowns (Lunar parity) -- 1.8.9 stand-in.
 *
 * On every other version this shows each item the player has on cooldown (ender
 * pearls, chorus fruit, a disabled shield, wind charges) as a dial. 1.8.9 has NO
 * item cooldowns at all: the ItemCooldowns system (and the hotbar's white sweep)
 * arrived in 1.9, so nothing on 1.8.9 ever goes on cooldown and there is nothing
 * to show. The foundation hides the Cooldowns card in the mod menu on 1.8.9; this
 * class keeps the 1.16.5 API so HudElements' "cooldowns" element compiles and
 * simply measures empty -- an element measuring 0x0 never draws and never shows
 * up in the HUD editor, exactly like a hidden HUD piece on the other versions.
 */
public final class CooldownsHud {
	private CooldownsHud() {
	}

	/** Always empty on 1.8.9 (no cooldown items). */
	public static int[] size(Minecraft mc) {
		return new int[]{0, 0};
	}

	/** Nothing to draw on 1.8.9 (no cooldown items). */
	public static void render(Gfx g, Minecraft mc, int w, int h) {
	}
}
