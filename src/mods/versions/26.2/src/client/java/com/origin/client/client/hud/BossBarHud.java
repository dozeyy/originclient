package com.origin.client.client.hud;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;

import java.util.Collection;

/**
 * Boss Bar (Lunar parity): the vanilla boss bar, movable in Edit HUD, scalable,
 * with the bar or the name hideable and an optional custom bar colour.
 *
 * Same split as the Scoreboard: the "bossbar" HUD element is only the drag
 * target + sample in the editor; the real bars are drawn by BossBarMixin, from
 * vanilla's own layer (same z-order as vanilla) at this element's HudPos.
 * The layout is vanilla's, verbatim (1.21.1 BossHealthOverlay): name 9px above
 * a 182x5 bar, 19px per boss, and no new rows past a third of the screen.
 *
 * PER-VERSION DELTA (1.21.11, BossHealthOverlay source-verified): the bar
 * sprites blit through RenderPipelines.GUI_TEXTURED with the custom colour on
 * the per-blit ARGB (no shader colour in the deferred GUI), text colours carry
 * an explicit alpha (1.21.6+ skips alpha-less text), pose is a Matrix3x2fStack.
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
	public static void render(GuiGraphicsExtractor g, Collection<LerpingBossEvent> events) {
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
		pose.pushMatrix();
		try {
			pose.translate((float) x, (float) y);
			pose.scale(s, s);
			int row = 0;
			for (LerpingBossEvent e : events) {
				drawOne(g, e.getName(), e, row);
				row += STEP;
				if (y + row * s >= sh / 3.0) {
					break;   // vanilla's cap: bars never fill more than a third of the screen
				}
			}
		} finally {
			pose.popMatrix();
		}
	}

	/** Editor sample: one boss, in the element's local space. */
	static void renderSample(GuiGraphicsExtractor g) {
		float s = scale();
		var pose = g.pose();
		pose.pushMatrix();
		pose.scale(s, s);
		drawOne(g, SAMPLE.getName(), SAMPLE, 0);
		pose.popMatrix();
	}

	private static void drawOne(GuiGraphicsExtractor g, Component name, BossEvent e, int top) {
		Minecraft mc = Minecraft.getInstance();
		if (Mods.bool(MOD, "renderText")) {
			int tw = mc.font.width(name);
			g.text(mc.font, name, (W - tw) / 2, top, 0xFFFFFFFF);
		}
		if (Mods.bool(MOD, "renderBar")) {
			drawBar(g, top + 9, e);
		}
	}

	// Vanilla's drawBar, plus Custom Bar Color: the WHITE sprites tinted to the
	// chosen colour through the per-blit ARGB.
	private static void drawBar(GuiGraphicsExtractor g, int y, BossEvent e) {
		boolean custom = Mods.bool(MOD, "customColor");
		String color = custom ? BossEvent.BossBarColor.WHITE.getName() : e.getColor().getName();
		int tint = custom ? 0xFF000000 | (OriginColorPicker.liveColor(MOD, "barColor") & 0xFFFFFF) : 0xFFFFFFFF;
		bar(g, y, W, color, "background", e.getOverlay(), tint);
		int filled = Mth.lerpDiscrete(e.getProgress(), 0, W);
		if (filled > 0) {
			bar(g, y, filled, color, "progress", e.getOverlay(), tint);
		}
	}

	private static void bar(GuiGraphicsExtractor g, int y, int width, String color, String part,
							BossEvent.BossBarOverlay overlay, int tint) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace("boss_bar/" + color + "_" + part),
				W, 5, 0, 0, 0, y, width, 5, tint);
		if (overlay != BossEvent.BossBarOverlay.PROGRESS) {
			g.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace("boss_bar/" + overlay.getName() + "_" + part),
					W, 5, 0, 0, 0, y, width, 5, tint);
		}
	}
}
