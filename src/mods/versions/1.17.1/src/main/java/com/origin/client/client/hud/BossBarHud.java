package com.origin.client.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.BossEvent;

import java.util.Collection;

/**
 * Boss Bar (Lunar parity): the vanilla boss bar, movable in Edit HUD, scalable,
 * with the bar or the name hideable and an optional custom bar colour.
 *
 * Same split as the Scoreboard: the "bossbar" HUD element is only the drag
 * target + sample in the editor; the real bars are drawn by BossBarMixin, from
 * vanilla's own overlay (same z-order as vanilla) at this element's HudPos.
 * The layout is vanilla's (BossHealthOverlay): name 9px above a 182x5 bar,
 * 19px per boss, and no new rows past a third of the screen.
 *
 * 1.17.1 port: no boss_bar/* sprites (1.20.2+) -- bars come from
 * textures/gui/bars.png. Live bars are drawn by VANILLA's own drawBar (handed in
 * by BossBarMixin), because the progress getter was renamed inside this era
 * (getPercent on 1.16, getProgress on 1.18) and drawBar reads it itself; Custom
 * Bar Color swaps the event to the WHITE row for that one call and tints it.
 * The editor sample has no event, so its bar is blitted here from bars.png.
 */
public final class BossBarHud {
	private static final String MOD = "bossbar";
	static final int W = 182, H = 14;   // one boss: name row + bar
	private static final int STEP = 19;
	private static final ResourceLocation BARS = new ResourceLocation("textures/gui/bars.png");
	private static final Component SAMPLE_NAME = new TextComponent("Ender Dragon");

	/** Vanilla's private BossHealthOverlay.drawBar(PoseStack, x, y, BossEvent). */
	public interface BarDrawer {
		void draw(PoseStack pose, int x, int y, BossEvent e);
	}

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
	public static void render(Gfx g, Collection<LerpingBossEvent> events, BarDrawer vanillaBar) {
		if (events.isEmpty()) {
			return;
		}
		HudPos p = pos();
		int[] sz = size();
		double w = sz[0] * p.scale, h = sz[1] * p.scale;
		int sw = g.guiWidth(), sh = g.guiHeight();
		double x = p.x(sw, w), y = p.y(sh, h);
		float s = (float) (scale() * p.scale);
		PoseStack pose = g.pose();
		pose.pushPose();
		try {
			pose.translate(x, y, 0);
			pose.scale(s, s, 1f);
			int row = 0;
			for (LerpingBossEvent e : events) {
				drawName(g, e.getName(), row);
				if (Mods.bool(MOD, "renderBar")) {
					drawLiveBar(g, row + 9, e, vanillaBar);
				}
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
	static void renderSample(Gfx g) {
		float s = scale();
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.scale(s, s, 1f);
		drawName(g, SAMPLE_NAME, 0);
		if (Mods.bool(MOD, "renderBar")) {
			drawSampleBar(g, 9, BossEvent.BossBarColor.PINK, 0.7f);
		}
		pose.popPose();
	}

	private static void drawName(Gfx g, Component name, int top) {
		if (Mods.bool(MOD, "renderText")) {
			Minecraft mc = Minecraft.getInstance();
			int tw = mc.font.width(name);
			g.drawString(mc.font, name, (W - tw) / 2, top, 0xFFFFFF);
		}
	}

	/** Custom Bar Color: the colour as a shader tint (applied to the WHITE row). */
	private static boolean beginTint() {
		boolean custom = Mods.bool(MOD, "customColor");
		if (custom) {
			int c = OriginColorPicker.liveColor(MOD, "barColor");
			RenderSystem.setShaderColor(((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, 1f);
		} else {
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
		RenderSystem.setShaderTexture(0, BARS);
		RenderSystem.enableBlend();
		return custom;
	}

	private static void endTint() {
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		RenderSystem.disableBlend();
	}

	// Vanilla's drawBar on the live event; with Custom Bar Color the event shows
	// the WHITE row for this one call (render thread only, restored at once).
	private static void drawLiveBar(Gfx g, int y, BossEvent e, BarDrawer vanillaBar) {
		boolean custom = beginTint();
		BossEvent.BossBarColor original = e.getColor();
		try {
			if (custom) {
				e.setColor(BossEvent.BossBarColor.WHITE);
			}
			vanillaBar.draw(g.pose(), 0, y, e);
		} finally {
			if (custom) {
				e.setColor(original);
			}
			endTint();
		}
	}

	// The same rows drawBar blits from bars.png: background at v = colour * 10,
	// fill 5px below it; PROGRESS has no notch overlay.
	private static void drawSampleBar(Gfx g, int y, BossEvent.BossBarColor color, float progress) {
		boolean custom = beginTint();
		try {
			int v = (custom ? BossEvent.BossBarColor.WHITE : color).ordinal() * 5 * 2;
			g.blit(BARS, 0, y, 0, v, W, 5);
			int filled = (int) (progress * 183.0F);
			if (filled > 0) {
				g.blit(BARS, 0, y, 0, v + 5, filled, 5);
			}
		} finally {
			endTint();
		}
	}
}
