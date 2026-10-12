package com.origin.client.client.hud;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.GuiTris;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cooldowns (Lunar parity): every item the local player has on cooldown --
 * ender pearls, chorus fruit, a disabled shield, wind charges -- as a dial with
 * the item in the middle, the remaining time sweeping round it, and the seconds
 * left underneath. A row that grows with each new cooldown, newest last.
 *
 * Vanilla's ItemCooldowns won't list its entries, so ItemCooldownsMixin reports
 * each cooldown here; the remaining fraction itself always comes from
 * vanilla's own getCooldownPercent, so the dial matches the hotbar's white
 * cooldown overlay exactly.
 *
 * Colours (Lunar's four): the sweep fades from Circle Start to Circle End as
 * the cooldown runs down, Edge rings the dial, Text is the seconds label.
 *
 * PER-VERSION DELTA (1.21.11, ItemCooldowns source-verified): cooldowns are
 * keyed by a cooldown GROUP id (an item's id, or its use_cooldown component's
 * group), not by Item. The group arrives through addCooldown(Identifier, int);
 * the icon comes from the stack that started it (addCooldown(ItemStack, int)) or,
 * for a server packet that only names the group, from the item with that id.
 * The dials are GuiTris triangles (the GUI is deferred here; 1.21.1 drew them
 * immediate-mode), and the pose is a Matrix3x2fStack.
 */
public final class CooldownsHud {
	private static final String MOD = "cooldowns";
	private static final int CELL = 24;     // one dial's column width
	private static final int DIAL = 20;     // dial diameter
	private static final int HEIGHT = 32;   // dial + seconds label

	private record Tracked(ItemStack icon, int ticks) {
	}

	// group -> icon + total length in ticks, insertion-ordered (oldest first).
	// Touched only on the client thread: the mixin drops every other caller.
	private static final Map<Identifier, Tracked> TOTALS = new LinkedHashMap<>();
	// The stack whose use is starting a cooldown right now (addCooldown(ItemStack)
	// HEAD -> addCooldown(Identifier) a moment later), for the icon.
	private static ItemStack pendingIcon = null;

	private CooldownsHud() {
	}

	private static boolean mine(ItemCooldowns cooldowns) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && cooldowns == mc.player.getCooldowns() && mc.isSameThread();
	}

	/** From ItemCooldownsMixin: addCooldown(ItemStack, int) is about to resolve its group. */
	public static void onStackCooldown(ItemCooldowns cooldowns, ItemStack stack) {
		if (mine(cooldowns) && stack != null && !stack.isEmpty()) {
			pendingIcon = stack.copyWithCount(1);
		}
	}

	/** From ItemCooldownsMixin -- only the local player's own cooldowns count. */
	public static void onCooldownAdded(ItemCooldowns cooldowns, Identifier group, int ticks) {
		ItemStack icon = pendingIcon;
		pendingIcon = null;
		if (!mine(cooldowns) || group == null || ticks <= 0) {
			return;
		}
		if (icon == null || !group.equals(cooldowns.getCooldownGroup(icon))) {
			var item = BuiltInRegistries.ITEM.getValue(group);
			icon = item == null || item == Items.AIR ? null : new ItemStack(item);
		}
		if (icon == null) {
			return;   // a custom group with no item of that name: nothing to show it as
		}
		TOTALS.remove(group);   // re-adding moves it to the end of the row
		TOTALS.put(group, new Tracked(icon, ticks));
	}

	private record Entry(ItemStack icon, float fraction, float secondsLeft) {
	}

	private static List<Entry> entries(Minecraft mc) {
		List<Entry> out = new ArrayList<>();
		if (mc.player != null) {
			ItemCooldowns cd = mc.player.getCooldowns();
			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			Iterator<Map.Entry<Identifier, Tracked>> it = TOTALS.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<Identifier, Tracked> e = it.next();
				float f = cd.getCooldownPercent(e.getValue().icon(), partial);
				if (f <= 0f) {
					it.remove();   // finished (or a new world/player): forget it
					continue;
				}
				out.add(new Entry(e.getValue().icon(), f, f * e.getValue().ticks() / 20f));
			}
		} else {
			TOTALS.clear();
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
		pose.pushMatrix();
		pose.scale(s, s);
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
			GuiTris dial = GuiTris.color();
			pie(dial, cx, cy, DIAL / 2f - 1, e.fraction(), sweep);
			ring(dial, cx, cy, DIAL / 2f, DIAL / 2f - 1.2f, edge);
			dial.submit(g);
			g.renderItem(e.icon(), (int) (cx - 8), (int) (cy - 8));
			String label = e.secondsLeft() >= 10 ? String.format(Locale.ROOT, "%.0f", e.secondsLeft())
					: String.format(Locale.ROOT, "%.1f", e.secondsLeft());
			g.drawString(mc.font, label, (int) (cx - mc.font.width(label) / 2f), DIAL + 3, text, true);
			x += CELL;
		}
		pose.popMatrix();
	}

	// ---- dial geometry (plain coloured triangles) ----

	private static void pie(GuiTris t, float cx, float cy, float r, float fraction, int argb) {
		if (fraction <= 0f || ((argb >>> 24) & 0xFF) == 0) {
			return;
		}
		int segs = Math.max(2, (int) Math.ceil(48 * fraction));
		for (int i = 0; i < segs; i++) {
			double a0 = angle(fraction * i / segs), a1 = angle(fraction * (i + 1) / segs);
			t.tri(cx, cy, cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r,
					cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r, argb);
		}
	}

	private static void ring(GuiTris t, float cx, float cy, float rOut, float rIn, int argb) {
		if (((argb >>> 24) & 0xFF) == 0) {
			return;
		}
		int segs = 48;
		for (int i = 0; i < segs; i++) {
			double a0 = angle(i / (double) segs), a1 = angle((i + 1) / (double) segs);
			float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
			t.quad(cx + c0 * rOut, cy + s0 * rOut, cx + c1 * rOut, cy + s1 * rOut,
					cx + c1 * rIn, cy + s1 * rIn, cx + c0 * rIn, cy + s0 * rIn, argb);
		}
	}

	/** 0 = 12 o'clock, increasing clockwise on screen (GUI y points down). */
	private static double angle(double turns) {
		return -Math.PI / 2 + turns * Math.PI * 2;
	}
}
