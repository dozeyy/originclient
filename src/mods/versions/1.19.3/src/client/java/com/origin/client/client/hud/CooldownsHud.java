package com.origin.client.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Era;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;

import java.util.ArrayList;
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
 * Colours (Lunar's four): the sweep fades from Circle Start to Circle End as
 * the cooldown runs down, Edge rings the dial, Text is the seconds label.
 */
public final class CooldownsHud {
	private static final String MOD = "cooldowns";
	private static final int CELL = 24;     // one dial's column width
	private static final int DIAL = 20;     // dial diameter
	private static final int HEIGHT = 32;   // dial + seconds label

	// item -> total cooldown length in ticks, insertion-ordered (oldest first).
	// Touched only on the client thread: onCooldownAdded drops every other caller.
	private static final Map<Item, Integer> TOTALS = new LinkedHashMap<>();

	private CooldownsHud() {
	}

	/** From ItemCooldownsMixin -- only the local player's own cooldowns count. */
	public static void onCooldownAdded(ItemCooldowns cooldowns, Item item, int ticks) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || cooldowns != mc.player.getCooldowns() || ticks <= 0) {
			return;
		}
		TOTALS.remove(item);   // re-adding moves it to the end of the row
		TOTALS.put(item, ticks);
	}

	private record Entry(Item item, float fraction, float secondsLeft) {
	}

	private static List<Entry> entries(Minecraft mc) {
		List<Entry> out = new ArrayList<>();
		if (mc.player != null) {
			ItemCooldowns cd = mc.player.getCooldowns();
			float partial = mc.getFrameTime();
			Iterator<Map.Entry<Item, Integer>> it = TOTALS.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<Item, Integer> e = it.next();
				float f = cd.getCooldownPercent(e.getKey(), partial);
				if (f <= 0f) {
					it.remove();   // finished (or a new world/player): forget it
					continue;
				}
				out.add(new Entry(e.getKey(), f, f * e.getValue() / 20f));
			}
		} else {
			TOTALS.clear();
		}
		if (out.isEmpty() && HudElements.editorPreview) {
			out.add(new Entry(Items.ENDER_PEARL, 0.65f, 0.65f));
			out.add(new Entry(Items.SHIELD, 0.3f, 1.5f));
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

	public static void render(Gfx g, Minecraft mc, int w, int h) {
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
			Matrix4f m = pose.last().pose();
			pie(m, cx, cy, DIAL / 2f - 1, e.fraction(), sweep);
			ring(m, cx, cy, DIAL / 2f, DIAL / 2f - 1.2f, edge);
			g.renderItem(new ItemStack(e.item()), (int) (cx - 8), (int) (cy - 8));
			String label = e.secondsLeft() >= 10 ? String.format("%.0f", e.secondsLeft())
					: String.format("%.1f", e.secondsLeft());
			g.drawString(mc.font, label, (int) (cx - mc.font.width(label) / 2f), DIAL + 3, text, true);
			x += CELL;
		}
		pose.popPose();
	}

	// ---- immediate-mode dial geometry (plain coloured triangles) ----

	private static void pie(Matrix4f m, float cx, float cy, float r, float fraction, int argb) {
		if (fraction <= 0f || ((argb >>> 24) & 0xFF) == 0) {
			return;
		}
		int segs = Math.max(2, (int) Math.ceil(48 * fraction));
		BufferBuilder b = begin();
		for (int i = 0; i < segs; i++) {
			double a0 = angle(fraction * i / segs), a1 = angle(fraction * (i + 1) / segs);
			vertex(b, m, cx, cy, argb);
			vertex(b, m, cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r, argb);
			vertex(b, m, cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r, argb);
		}
		draw(b);
	}

	private static void ring(Matrix4f m, float cx, float cy, float rOut, float rIn, int argb) {
		if (((argb >>> 24) & 0xFF) == 0) {
			return;
		}
		int segs = 48;
		BufferBuilder b = begin();
		for (int i = 0; i < segs; i++) {
			double a0 = angle(i / (double) segs), a1 = angle((i + 1) / (double) segs);
			float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
			vertex(b, m, cx + c0 * rOut, cy + s0 * rOut, argb);
			vertex(b, m, cx + c1 * rOut, cy + s1 * rOut, argb);
			vertex(b, m, cx + c1 * rIn, cy + s1 * rIn, argb);
			vertex(b, m, cx + c0 * rOut, cy + s0 * rOut, argb);
			vertex(b, m, cx + c1 * rIn, cy + s1 * rIn, argb);
			vertex(b, m, cx + c0 * rIn, cy + s0 * rIn, argb);
		}
		draw(b);
	}

	/** 0 = 12 o'clock, increasing clockwise on screen (GUI y points down). */
	private static double angle(double turns) {
		return -Math.PI / 2 + turns * Math.PI * 2;
	}

	private static BufferBuilder begin() {
		BufferBuilder b = Tesselator.getInstance().getBuilder();
		b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		return b;
	}

	private static void vertex(BufferBuilder b, Matrix4f m, float x, float y, int argb) {
		b.vertex(m, x, y, 0).color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF).endVertex();
	}

	private static void draw(BufferBuilder b) {
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();   // winding-agnostic: the fan's triangles face either way
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		Era.drawBuffer(b);
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}
}
