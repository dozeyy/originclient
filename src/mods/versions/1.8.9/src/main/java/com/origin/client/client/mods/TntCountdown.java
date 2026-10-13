package com.origin.client.client.mods;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.hooks.WorldLabel;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.item.EntityTNTPrimed;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * TNT Countdown (Lunar parity): "Fuse: 2.35" floating above every lit TNT.
 *
 * The fuse is EntityTNTPrimed.fuse -- counted down by the client every tick, so
 * it's exact -- minus the partial tick for smoothness, plus the Adjust Fuse Time
 * offset (servers with custom fuses). The number fades from Countdown Color
 * through yellow to red as the fuse burns (vanilla's 80-tick fuse as the full
 * scale), unless Static Countdown Color pins it.
 *
 * 1.8.9: `fuse` is a public field the client decrements itself in onUpdate (the
 * spawn packet doesn't carry it, so it starts at vanilla's 80). remaining() keeps
 * the newer versions' "does the fuse move?" watch anyway: if it moves it IS the
 * countdown; if it sits still while the entity ages, the countdown is fuse - age.
 *
 * Drawn like a vanilla name tag (hooks/WorldLabel: billboard at nametag scale, a
 * faint see-through pass with the optional backdrop, then the solid text).
 * Full-bright so it stays readable at night; 64-block range like name tags.
 * Called from TntCountdownMixin at the end of RenderTNTPrimed.doRender with the
 * entity's camera-relative position (1.8.9 renderers get x/y/z, not a posed stack).
 */
public final class TntCountdown {
	private static final String MOD = "tntcountdown";
	private static final float FULL_FUSE_TICKS = 80f;   // EntityTNTPrimed's default fuse
	private static final float SCALE = 0.025F;           // the newer versions' label scale

	private TntCountdown() {
	}

	// per TNT: {first fuse seen, ticksExisted then, mode (0 unknown, 1 fuse counts, 2 fuse fixed)}
	private static final Map<EntityTNTPrimed, int[]> SEEN = new WeakHashMap<>();

	private static int remaining(EntityTNTPrimed tnt) {
		int fuse = tnt.fuse;
		int[] s = SEEN.get(tnt);
		if (s == null) {
			s = new int[]{fuse, tnt.ticksExisted, 0};
			SEEN.put(tnt, s);
		}
		if (s[2] == 0) {
			if (fuse != s[0]) {
				s[2] = 1;
			} else if (tnt.ticksExisted - s[1] >= 3) {
				s[2] = 2;
			}
		}
		return s[2] == 2 ? fuse - tnt.ticksExisted : fuse;
	}

	public static void render(EntityTNTPrimed tnt, double x, double y, double z, float partialTick) {
		if (!Mods.on(MOD)) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.getRenderManager().livingPlayer != null
				&& tnt.getDistanceSqToEntity(mc.getRenderManager().livingPlayer) > 4096.0) {
			return;
		}
		float ticks = Math.max(0f, remaining(tnt) - partialTick + (float) Mods.num(MOD, "fuseOffset"));
		int numColor = OriginColorPicker.liveColor(MOD, "color");
		if (!Mods.bool(MOD, "staticColor")) {
			float r = Math.min(1f, ticks / FULL_FUSE_TICKS);
			numColor = r > 0.5f
					? OriginTheme.lerpColor(0xFFFFFF00, numColor, (r - 0.5f) * 2f)
					: OriginTheme.lerpColor(0xFFFF0000, 0xFFFFFF00, r * 2f);
		}
		String prefix = "Fuse: ";
		String number = String.format(Locale.ROOT, "%.2f", ticks / 20f);
		int prefixColor = 0xFF000000 | (OriginColorPicker.liveColor(MOD, "prefixColor") & 0xFFFFFF);
		numColor = 0xFF000000 | (numColor & 0xFFFFFF);

		FontRenderer font = mc.fontRendererObj;
		float w = font.getStringWidth(prefix + number);
		float x0 = -w / 2f;
		boolean shadow = Mods.bool(MOD, "textShadow");
		WorldLabel.begin(x, y + tnt.height + 0.5, z, SCALE, true);
		try {
			if (Mods.bool(MOD, "background")) {
				// vanilla's tag backdrop (options background opacity 0.25 on newer versions)
				WorldLabel.backdrop(x0 - 1, -1, x0 + w + 1, 8, 0x40000000);
			}
			float px = x0 + WorldLabel.text(prefix, x0, 0, 0x20FFFFFF, prefixColor, shadow, true);
			WorldLabel.text(number, px, 0, 0x20FFFFFF, numColor, shadow, true);
		} finally {
			WorldLabel.end();
		}
	}
}
