package com.origin.client.client.hud;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.origin.client.client.gui.Gfx;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

/**
 * Titles (Lunar parity): the /title title + subtitle, movable in Edit HUD,
 * scalable, each line hideable and recolourable.
 *
 * Same split as the Scoreboard: the "titles" HUD element is the drag target +
 * editor sample; the real titles are drawn by TitlesMixin from vanilla's title
 * layer. Geometry is vanilla's (1.21.1 Gui.renderTitle): title at 4x, subtitle
 * at 2x, both around one origin -- title baseline row at -10, subtitle at +5 in
 * their own scaled space, i.e. the title spans -40..-4 and the subtitle 10..28
 * around the origin. The element box covers that span for the sample text;
 * the origin sits 40px (x scale) below the box top, horizontally centred.
 */
public final class TitlesHud {
	private static final String MOD = "titles";
	private static final Component SAMPLE_TITLE = new TextComponent("Title");
	private static final Component SAMPLE_SUBTITLE = new TextComponent("Subtitle");
	private static final int H = 68;          // -40 (title top) .. +28 (subtitle bottom)
	private static final int ORIGIN_Y = 40;   // origin's distance below the box top

	private TitlesHud() {
	}

	/** Default = vanilla's spot: origin on the screen centre (box centre is 6px lower). */
	public static HudPos defaultPos() {
		return new HudPos(4, 0, -6, 1.0);
	}

	public static HudPos pos() {
		return HudPos.load("titles", defaultPos());
	}

	static float scale() {
		double s = Mods.num(MOD, "scale");
		float base = (float) (s <= 0.05 ? 1.0 : s);
		return base * BossBarHud.guiScaleFix(MOD);
	}

	/** Unscaled box width: the wider of the sample title (4x) and subtitle (2x). */
	private static int baseWidth(Font font) {
		return Math.max(font.width(SAMPLE_TITLE) * 4, font.width(SAMPLE_SUBTITLE) * 2);
	}

	/** Real box size (used to place the in-game titles). */
	static int[] boxSize() {
		float s = scale();
		return new int[]{Math.round(baseWidth(Minecraft.getInstance().font) * s), Math.round(H * s)};
	}

	/** What Edit HUD sees: nothing unless "Show in HUD Editor" is on (Lunar's default off). */
	public static int[] editorSize() {
		return Mods.bool(MOD, "showInHudEditor") ? boxSize() : new int[]{0, 0};
	}

	/** In-game draw from TitlesMixin; alpha is vanilla's fade (0-255). */
	public static void render(Gfx g, Component title, Component subtitle, int alpha) {
		HudPos p = pos();
		int[] sz = boxSize();
		double w = sz[0] * p.scale, h = sz[1] * p.scale;
		double x = p.x(g.guiWidth(), w), y = p.y(g.guiHeight(), h);
		float s = (float) (scale() * p.scale);
		com.mojang.blaze3d.vertex.PoseStack pose = g.pose();
		pose.pushPose();
		try {
			pose.translate(x, y, 0);
			pose.scale(s, s, 1f);
			draw(g, title, subtitle, alpha);
		} finally {
			pose.popPose();
		}
	}

	/** Editor sample, in the element's local (already translated + HudPos-scaled) space. */
	static void renderSample(Gfx g) {
		float s = scale();
		com.mojang.blaze3d.vertex.PoseStack pose = g.pose();
		pose.pushPose();
		pose.scale(s, s, 1f);
		draw(g, SAMPLE_TITLE, SAMPLE_SUBTITLE, 255);
		pose.popPose();
	}

	// Box-local, unscaled: origin at (baseWidth/2, ORIGIN_Y). Keep Title Centered
	// centres each line on the origin; off, lines start at the box's left edge.
	private static void draw(Gfx g, Component title, Component subtitle, int alpha) {
		Font font = Minecraft.getInstance().font;
		int bw = baseWidth(font);
		boolean centered = Mods.bool(MOD, "keepCentered");
		com.mojang.blaze3d.vertex.PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(bw / 2f, ORIGIN_Y, 0);
		if (title != null && Mods.bool(MOD, "showTitle")) {
			pose.pushPose();
			pose.scale(4f, 4f, 4f);
			int tw = font.width(title);
			int tx = centered ? -tw / 2 : -bw / 8;
			g.drawStringWithBackdrop(font, title, tx, -10, tw, color("titleColor", alpha));
			pose.popPose();
		}
		if (subtitle != null && Mods.bool(MOD, "showSubtitle")) {
			pose.pushPose();
			pose.scale(2f, 2f, 2f);
			int sw = font.width(subtitle);
			int sx = centered ? -sw / 2 : -bw / 4;
			g.drawStringWithBackdrop(font, subtitle, sx, 5, sw, color("subtitleColor", alpha));
			pose.popPose();
		}
		pose.popPose();
	}

	// The option colour's RGB with vanilla's fade alpha. Text the server coloured
	// keeps its own colour; this only shows on uncoloured text (vanilla = white).
	private static int color(String key, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (OriginColorPicker.liveColor(MOD, key) & 0xFFFFFF);
	}
}
