package com.origin.client.client.hud;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.GuiMesh;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cooldowns (Lunar parity): every item the local player has on cooldown --
 * ender pearls, chorus fruit, a disabled shield, wind charges -- as a dial with
 * the item in the middle, the remaining time sweeping round it, and the seconds
 * left underneath. A row that grows with each new cooldown, newest last.
 *
 * Vanilla's ItemCooldowns won't list its entries, so ItemCooldownsMixin reports
 * each addCooldown here; the remaining fraction itself always comes from
 * vanilla's own getCooldownPercent, so the dial matches the hotbar's white
 * cooldown overlay exactly.
 *
 * PER-VERSION (1.21.2+): cooldowns are kept per cooldown GROUP (a
 * ResourceLocation -- the item's id unless its use_cooldown component names a
 * shared group), not per Item. The packet path only knows the group, so the
 * icon is the stack that last started that group (addCooldown(ItemStack, int)),
 * or the item registered under the group's id. Dials are drawn with GuiMesh.
 *
 * Colours (Lunar's four): the sweep fades from Circle Start to Circle End as
 * the cooldown runs down, Edge rings the dial, Text is the seconds label.
 */
public final class CooldownsHud {
	private static final String MOD = "cooldowns";
	private static final int CELL = 24;     // one dial's column width
	private static final int DIAL = 20;     // dial diameter
	private static final int HEIGHT = 32;   // dial + seconds label
	// group -> total cooldown length in ticks, insertion-ordered (oldest first).
	// Touched only on the client thread: the hooks drop every other caller.
	private static final Map<ResourceLocation, Integer> TOTALS = new LinkedHashMap<>();
	// group -> the stack drawn for it (and asked for its remaining fraction)
	private static final Map<ResourceLocation, ItemStack> ICONS = new HashMap<>();

	private CooldownsHud() {
	}

	private static boolean mine(ItemCooldowns cooldowns) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && cooldowns == mc.player.getCooldowns() && mc.isSameThread();
	}

	/** From ItemCooldownsMixin: addCooldown(ItemStack, int) -- remembers the icon. */
	public static void onStackCooldown(ItemCooldowns cooldowns, ItemStack stack) {
		if (stack == null || stack.isEmpty() || !mine(cooldowns)) {
			return;
		}
		ICONS.put(cooldowns.getCooldownGroup(stack), stack.copyWithCount(1));
	}

	/** From ItemCooldownsMixin: addCooldown(ResourceLocation, int) -- every cooldown lands here. */
	public static void onCooldownAdded(ItemCooldowns cooldowns, ResourceLocation group, int ticks) {
		if (group == null || ticks <= 0 || !mine(cooldowns)) {
			return;
		}
		if (!ICONS.containsKey(group)) {
			var item = BuiltInRegistries.ITEM.getOptional(group);
			if (item.isEmpty()) {
				return;   // a custom group we have no picture for
			}
			ICONS.put(group, new ItemStack(item.get()));
		}
		TOTALS.remove(group);   // re-adding moves it to the end of the row
		TOTALS.put(group, ticks);
	}

	private record Entry(ItemStack icon, float fraction, float secondsLeft) {
	}

	private static List<Entry> entries(Minecraft mc) {
		List<Entry> out = new ArrayList<>();
		if (mc.player != null) {
			ItemCooldowns cd = mc.player.getCooldowns();
			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			Iterator<Map.Entry<ResourceLocation, Integer>> it = TOTALS.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<ResourceLocation, Integer> e = it.next();
				ItemStack icon = ICONS.get(e.getKey());
				float f = icon == null ? 0f : cd.getCooldownPercent(icon, partial);
				if (f <= 0f) {
					it.remove();   // finished (or a new world/player): forget it
					continue;
				}
				out.add(new Entry(icon, f, f * e.getValue() / 20f));
			}
		} else {
			TOTALS.clear();
			ICONS.clear();
		}
		if (out.isEmpty() && HudElements.editorPreview) {
			out.add(new Entry(new ItemStack(Items.ENDER_PEARL), 0.65f, 0.65f));
			out.add(new Entry(new ItemStack(Items.SHIELD), 0.3f, 1.5f));
		}
		return out;
	}

	/** The mod's own Scale slider. */
	private static float scale() {
		double s = Mods.num(MOD, "scale");
		return (float) (s <= 0.05 ? 1.0 : s);
	}

	public static int[] size(Minecraft mc) {
		float s = scale();
		int n = Math.max(1, entries(mc).size());
		return new int[]{(int) Math.ceil(n * CELL * s), (int) Math.ceil(HEIGHT * s)};
	}

	public static void render(GuiGraphics g, Minecraft mc, int w, int h) {
		List<Entry> list = entries(mc);
		if (list.isEmpty()) {
			return;   // nothing cooling down: draw nothing at all
		}
		float s = scale();
		var pose = g.pose();
		pose.pushPose();
		pose.scale(s, s, 1f);
		int start = OriginColorPicker.liveColor(MOD, "circleStart");
		int end = OriginColorPicker.liveColor(MOD, "circleEnd");
		int edge = OriginColorPicker.liveColor(MOD, "edgeColor");
		int text = OriginColorPicker.liveColor(MOD, "textColor");
		int x = 0;
		for (Entry e : list) {
			float cx = x + CELL / 2f, cy = DIAL / 2f + 1;
			// Sweep = the time still left, clockwise from 12 o'clock; it shades from
			// Start toward End as the cooldown runs out.
			int sweep = OriginTheme.lerpColor(end, start, e.fraction());
			GuiMesh mesh = GuiMesh.colors();
			pie(mesh, cx, cy, DIAL / 2f - 1, e.fraction(), sweep);
			ring(mesh, cx, cy, DIAL / 2f, DIAL / 2f - 1.2f, edge);
			mesh.draw(g);
			g.renderItem(e.icon(), (int) (cx - 8), (int) (cy - 8));
			String label = e.secondsLeft() >= 10 ? String.format("%.0f", e.secondsLeft())
					: String.format("%.1f", e.secondsLeft());
			g.drawString(mc.font, label, (int) (cx - mc.font.width(label) / 2f), DIAL + 3, text, true);
			x += CELL;
		}
		pose.popPose();
	}

	// ---- dial geometry (plain coloured triangles) ----

	private static void pie(GuiMesh mesh, float cx, float cy, float r, float fraction, int argb) {
		if (fraction <= 0f || ((argb >>> 24) & 0xFF) == 0) {
			return;
		}
		int segs = Math.max(2, (int) Math.ceil(48 * fraction));
		for (int i = 0; i < segs; i++) {
			double a0 = angle(fraction * i / segs), a1 = angle(fraction * (i + 1) / segs);
			mesh.tri(cx, cy,
					cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r,
					cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r, argb);
		}
	}

	private static void ring(GuiMesh mesh, float cx, float cy, float rOut, float rIn, int argb) {
		if (((argb >>> 24) & 0xFF) == 0) {
			return;
		}
		int segs = 48;
		for (int i = 0; i < segs; i++) {
			double a0 = angle(i / (double) segs), a1 = angle((i + 1) / (double) segs);
			float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
			mesh.quad(cx + c0 * rOut, cy + s0 * rOut, cx + c1 * rOut, cy + s1 * rOut,
					cx + c1 * rIn, cy + s1 * rIn, cx + c0 * rIn, cy + s0 * rIn, argb);
		}
	}

	/** 0 = 12 o'clock, increasing clockwise on screen (GUI y points down). */
	private static double angle(double turns) {
		return -Math.PI / 2 + turns * Math.PI * 2;
	}
}
