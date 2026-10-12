package com.origin.client.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.BossEvent;

import java.util.Collection;

/**
 * Boss Bar (Lunar parity): the vanilla boss bar, movable in Edit HUD, scalable,
 * with the bar or the name hideable and an optional custom bar colour.
 *
 * Same split as the Scoreboard: the "bossbar" HUD element is only the drag
 * target + sample in the editor; the real bars are drawn by BossBarMixin, from
 * vanilla's own layer (same z-order as vanilla) at this element's HudPos.
 * The layout is vanilla's, verbatim (1.20 BossHealthOverlay): name 9px above
 * a 182x5 bar, 19px per boss, and no new rows past a third of the screen.
 */
public final class BossBarHud {
	private static final String MOD = "bossbar";
	static final int W = 182, H = 14;   // one boss: name row + bar
	private static final int STEP = 19;
	private static final BossEvent SAMPLE = new LerpingBossEvent(new java.util.UUID(0L, 0L),
			Component.literal("Ender Dragon"), 0.7f, BossEvent.BossBarColor.PINK,
			BossEvent.BossBarOverlay.PROGRESS, false, false, false);

	private BossBarHud() {
	}

	/** Default = exactly vanilla's spot: top centre, name at y=3. */
	public static HudPos defaultPos() {
		return new HudPos(1, 0, 3, 1.0);
	}

	public static HudPos pos() {
		return HudPos.load("bossbar", defaultPos());
	}

	/** Mod Scale x the GUI-scale opt-out, shared by measure and render. */
	static float scale() {
		double s = Mods.num(MOD, "scale");
		float base = (float) (s <= 0.05 ? 1.0 : s);
		return base * guiScaleFix(MOD);
	}

	/** "Use Minecraft GUI Scale" off: counter the GUI scale so the element keeps
	 *  the size it has at GUI Scale 2, whatever the player's setting. */
	static float guiScaleFix(String modId) {
		if (Mods.bool(modId, "useGuiScale")) {
			return 1f;
		}
		double gs = Minecraft.getInstance().getWindow().getGuiScale();
		return gs <= 0 ? 1f : (float) (2.0 / gs);
	}

	public static int[] size() {
		float s = scale();
		return new int[]{Math.round(W * s), Math.round(H * s)};
	}

	/** Draws every live boss bar at the element's position (BossBarMixin). */
	public static void render(GuiGraphics g, Collection<LerpingBossEvent> events) {
		if (events.isEmpty()) {
			return;
		}
		HudPos p = pos();
		int[] sz = size();
		double w = sz[0] * p.scale, h = sz[1] * p.scale;
		int sw = g.guiWidth(), sh = g.guiHeight();
		double x = p.x(sw, w), y = p.y(sh, h);
		float s = (float) (scale() * p.scale);
		var pose = g.pose();
		pose.pushPose();
		try {
			pose.translate(x, y, 0);
			pose.scale(s, s, 1f);
			int row = 0;
			for (LerpingBossEvent e : events) {
				drawOne(g, e.getName(), e, row);
				row += STEP;
				if (y + row * s >= sh / 3.0) {
					break;   // vanilla's cap: bars never fill more than a third of the screen
				}
			}
		} finally {
			pose.popPose();
		}
	}

	/** Editor sample: one boss, in the element's local space. */
	static void renderSample(GuiGraphics g) {
		float s = scale();
		var pose = g.pose();
		pose.pushPose();
		pose.scale(s, s, 1f);
		drawOne(g, SAMPLE.getName(), SAMPLE, 0);
		pose.popPose();
	}

	private static void drawOne(GuiGraphics g, Component name, BossEvent e, int top) {
		Minecraft mc = Minecraft.getInstance();
		if (Mods.bool(MOD, "renderText")) {
			int tw = mc.font.width(name);
			g.drawString(mc.font, name, (W - tw) / 2, top, 0xFFFFFF);
		}
		if (Mods.bool(MOD, "renderBar")) {
			drawBar(g, top + 9, e);
		}
	}

	// Vanilla's drawBar, plus Custom Bar Color: the WHITE bar tinted to the
	// chosen colour through the shader colour (reset right after).
	// 1.20 / 1.20.1 predate GUI sprites (1.20.2): every bar is a row of
	// textures/gui/bars.png (verified against decompiled 1.20 BossHealthOverlay):
	// colour rows at v = colour.ordinal() * 10, notch overlays at
	// v = 80 + (overlay.ordinal() - 1) * 10, +5 for the progress half; the fill is
	// (int) (progress * 183).
	private static final ResourceLocation BARS = new ResourceLocation("textures/gui/bars.png");

	private static void drawBar(GuiGraphics g, int y, BossEvent e) {
		boolean custom = Mods.bool(MOD, "customColor");
		BossEvent.BossBarColor color = custom ? BossEvent.BossBarColor.WHITE : e.getColor();
		if (custom) {
			int c = OriginColorPicker.liveColor(MOD, "barColor");
			RenderSystem.setShaderColor(((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, 1f);
		}
		try {
			bar(g, y, W, color, 0, e.getOverlay());
			int filled = (int) (e.getProgress() * 183.0F);
			if (filled > 0) {
				bar(g, y, filled, color, 5, e.getOverlay());
			}
		} finally {
			if (custom) {
				RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			}
		}
	}

	private static void bar(GuiGraphics g, int y, int width, BossEvent.BossBarColor color, int part,
							BossEvent.BossBarOverlay overlay) {
		RenderSystem.enableBlend();
		g.blit(BARS, 0, y, 0, color.ordinal() * 5 * 2 + part, width, 5);
		if (overlay != BossEvent.BossBarOverlay.PROGRESS) {
			g.blit(BARS, 0, y, 0, 80 + (overlay.ordinal() - 1) * 5 * 2 + part, width, 5);
		}
		RenderSystem.disableBlend();
	}
}
