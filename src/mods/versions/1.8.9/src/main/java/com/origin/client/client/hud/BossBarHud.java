package com.origin.client.client.hud;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.Pose;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.boss.BossStatus;
import net.minecraft.util.ResourceLocation;

/**
 * Boss Bar (Lunar parity): the vanilla boss bar, movable in Edit HUD, scalable,
 * with the bar or the name hideable and an optional custom bar colour.
 *
 * Same split as the Scoreboard: the "bossbar" HUD element is only the drag
 * target + sample in the editor; the real bar is drawn from vanilla's own boss
 * layer (same z-order as vanilla) at this element's HudPos.
 *
 * 1.8.9: there is ONE boss bar, held in BossStatus' statics (bossName,
 * healthScale, statusBarTime) -- no boss-event list, no colours/overlays. Vanilla
 * (GuiIngame.renderBossHealth) draws the name 10px above a 182x5 bar from
 * textures/gui/icons.png (background at v=74, fill at v=79, both twice), and
 * DECREMENTS statusBarTime every frame it draws -- that countdown is what makes
 * the bar vanish once the boss stops refreshing it. HudHooks cancels
 * RenderGameOverlayEvent.Pre(BOSSHEALTH) while the mod is on and calls
 * render() there, so render() keeps that per-frame countdown.
 * Custom Bar Color: 1.8.9's bar sprite is purple with no white row to tint (the
 * colour rows of bars.png are 1.9+), so a custom colour is drawn as flat fills
 * in that colour over a darkened track -- the same shape, the player's colour.
 */
public final class BossBarHud {
	private static final String MOD = "bossbar";
	static final int W = 182, H = 15;   // one boss: name row (10) + bar (5)
	private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");
	private static final String SAMPLE_NAME = "Ender Dragon";

	private BossBarHud() {
	}

	/** Default = exactly vanilla's spot: top centre, name at y=2. */
	public static HudPos defaultPos() {
		return new HudPos(1, 0, 2, 1.0);
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
		double gs = Gfx.guiScale();
		return gs <= 0 ? 1f : (float) (2.0 / gs);
	}

	public static int[] size() {
		float s = scale();
		return new int[]{Math.round(W * s), Math.round(H * s)};
	}

	/**
	 * Draws the live boss bar at the element's position (HudHooks, from the
	 * cancelled Pre(BOSSHEALTH)). Mirrors vanilla's guard and countdown.
	 */
	public static void render(Gfx g) {
		if (BossStatus.bossName == null || BossStatus.statusBarTime <= 0) {
			return;
		}
		--BossStatus.statusBarTime;   // vanilla's per-frame countdown (see the class comment)
		HudPos p = pos();
		int[] sz = size();
		double w = sz[0] * p.scale, h = sz[1] * p.scale;
		int sw = g.guiWidth(), sh = g.guiHeight();
		double x = p.x(sw, w), y = p.y(sh, h);
		float s = (float) (scale() * p.scale);
		Pose pose = g.pose();
		int depth = pose.depth();
		pose.pushPose();
		try {
			pose.translate(x, y, 0);
			pose.scale(s, s, 1f);
			drawName(g, BossStatus.bossName);
			if (Mods.bool(MOD, "renderBar")) {
				drawBar(g, 10, BossStatus.healthScale);
			}
		} finally {
			HudElements.popTo(pose, depth);
			Gfx.resetColor();
			// vanilla leaves icons.png bound for the hearts/armour drawn next
			Gfx.bind(ICONS);
		}
	}

	/** Editor sample: one boss, in the element's local space. */
	static void renderSample(Gfx g) {
		float s = scale();
		Pose pose = g.pose();
		pose.pushPose();
		pose.scale(s, s, 1f);
		drawName(g, SAMPLE_NAME);
		if (Mods.bool(MOD, "renderBar")) {
			drawBar(g, 10, 0.7f);
		}
		pose.popPose();
	}

	private static void drawName(Gfx g, String name) {
		if (Mods.bool(MOD, "renderText") && name != null) {
			Minecraft mc = Minecraft.getMinecraft();
			int tw = mc.fontRendererObj.getStringWidth(name);
			g.drawString(mc.fontRendererObj, name, (W - tw) / 2, 0, 0xFFFFFFFF);
		}
	}

	// Vanilla's bar: the track twice (as GuiIngame does), then the fill
	// healthScale * 183 px wide; or the Custom Bar Color fills.
	private static void drawBar(Gfx g, int y, float progress) {
		int filled = (int) (progress * (W + 1));
		if (Mods.bool(MOD, "customColor")) {
			int c = OriginColorPicker.liveColor(MOD, "barColor") | 0xFF000000;
			int r = (c >> 16) & 0xFF, gr = (c >> 8) & 0xFF, b = c & 0xFF;
			int track = 0xFF000000 | (r * 35 / 100 << 16) | (gr * 35 / 100 << 8) | (b * 35 / 100);
			g.fill(0, y, W, y + 5, track);
			if (filled > 0) {
				g.fill(0, y, Math.min(W, filled), y + 5, c);
			}
			return;
		}
		Gfx.resetColor();
		Gfx.defaultBlend();
		try {
			g.blit(ICONS, 0, y, 0, 74, W, 5);
			g.blit(ICONS, 0, y, 0, 74, W, 5);
			if (filled > 0) {
				g.blit(ICONS, 0, y, 0, 79, filled, 5);
			}
		} finally {
			GlStateManager.disableBlend();
		}
	}
}
