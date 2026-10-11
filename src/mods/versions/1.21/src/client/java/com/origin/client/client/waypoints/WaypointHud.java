package com.origin.client.client.waypoints;

import com.origin.client.client.mods.Mods;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector4f;

// The SCREEN-SPACE half of waypoint rendering: the ◆ icon, name, and distance,
// drawn as a HUD overlay at each waypoint's projected screen position (world →
// view → projection → NDC → gui pixels). Because this is a GUI pass drawn after
// the world:
//   - it is ALWAYS visible — through blocks, terrain, everything (only the
//     in-world beam is allowed to be occluded);
//   - world lighting / night darkness / shader pipelines can't touch it — the
//     text takes priority and stays exactly its configured colour;
//   - size is constant on screen, tuned per-waypoint by the icon/text/distance
//     scale bars.
// WaypointRenderer.capture() refreshes the matrices every world frame; a
// waypoint behind the camera (w <= 0) is simply not drawn — no edge markers.
public final class WaypointHud {
	private WaypointHud() {
	}

	private static final Matrix4f VIEW = new Matrix4f();
	private static final Matrix4f PROJ = new Matrix4f();
	private static double camX, camY, camZ;
	private static volatile boolean valid = false;

	/** Called from the world pass each frame with the live camera matrices.
	 *
	 *  The view transform must replicate EXACTLY what world geometry (the beam)
	 *  goes through at draw time: ModelViewMat (RenderSystem) ∘ the PoseStack
	 *  pose baked into the vertices. Depending on the version/pipeline the
	 *  camera rotation lives in either factor — composing both is correct in
	 *  every arrangement, where using just one (the old bug) projected with an
	 *  identity view and put every waypoint off-screen. */
	static void capture(WorldRenderContext ctx) {
		VIEW.set(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix());
		if (ctx.matrixStack() != null) {
			VIEW.mul(ctx.matrixStack().last().pose());
		}
		PROJ.set(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix());
		var c = ctx.camera().getPosition();
		camX = c.x;
		camY = c.y;
		camZ = c.z;
		valid = true;
	}

	public static void render(GuiGraphics g) {
		if (!valid || !Mods.on("waypoints")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		String dim = mc.level.dimension().location().toString();
		Font font = mc.font;
		int sw = g.guiWidth(), sh = g.guiHeight();

		for (Waypoints.Waypoint w : Waypoints.all()) {
			if (!w.enabled || !dim.equals(w.dimension)) {
				continue;
			}
			if (!w.showIcon && !w.showText && !w.showDistance) {
				continue;
			}
			// anchor: just above the waypoint block
			double wx = w.x + 0.5 - camX, wy = w.y + 1.8 - camY, wz = w.z + 0.5 - camZ;
			Vector4f p = new Vector4f((float) wx, (float) wy, (float) wz, 1f);
			VIEW.transform(p);
			PROJ.transform(p);
			if (p.w <= 0.05f) {
				continue;   // behind the camera — a waypoint is only shown when looked at
			}
			int sx = Math.round((p.x / p.w * 0.5f + 0.5f) * sw);
			int sy = Math.round((0.5f - p.y / p.w * 0.5f) * sh);
			if (sx < -60 || sx > sw + 60 || sy < -60 || sy > sh + 60) {
				continue;
			}
			double dist = Math.sqrt(wx * wx + wy * wy + wz * wz);

			int y = sy;
			if (w.showIcon) {
				int r = Math.max(2, (int) Math.round(6 * w.iconScale));   // floor 2 so 0.25× reads smaller than 0.5×
				marker(g, sx, y, r, opaque(w.iconColor), 255);   // same refined marker as the locator
				y += r + 4;
			}
			if (w.showText && !w.name.isEmpty()) {
				y += pillText(g, font, sx, y, w.name, (float) w.textScale, opaque(w.textColor), w.textBgColor);
			}
			if (w.showDistance) {
				pillText(g, font, sx, y, (int) Math.round(dist) + "m", (float) w.distScale,
						opaque(w.textColor), w.textBgColor);
			}
		}

	}

	// Entry point for BOTH locator-bar modes, called from the TAIL of the vanilla
	// hotbar HUD layer (GuiHudMixin) — the same layer/batch context vanilla uses to
	// draw the XP bar and its level number, so our gems draw over the bar sprite by
	// plain call order (drawing from a later hook let HUD batching reorder the
	// sprite over the gems — pixel-verified).
	public static void renderBars(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.options.hideGui
				|| !Mods.on("waypoints") || !Mods.bool("waypoints", "locatorBar")) {
			return;
		}
		if (!Mods.bool("waypoints", "separateBar")) {
			renderXpHostedBar(g, mc, g.guiWidth(), g.guiHeight());
		} else {
			// Separate/movable: draw at the element's saved HUD position + scale.
			var pos = com.origin.client.client.hud.HudPos.load("locatorbar",
					new com.origin.client.client.hud.HudPos(7, 0, -22, 1.0));
			int sw = g.guiWidth(), sh = g.guiHeight();
			double w = BAR_W * pos.scale, h = BAR_H * pos.scale;
			double x = pos.x(sw, w), y = pos.y(sh, h);
			var pose = g.pose();
			pose.pushPose();
			pose.translate(x, y, 0);
			pose.scale((float) pos.scale, (float) pos.scale, 1f);
			drawBar(g, mc, 0, 0, BAR_W, true);
			pose.popPose();
		}
	}

	// The Locator Bar: an XP-bar-styled compass strip. Each enabled waypoint (this
	// dimension, within 2000 blocks) is an ICON on the strip positioned by its
	// direction relative to the camera view — dead ahead = center, directly behind =
	// clustered at BOTH far ends. Six distance stages scale size + opacity (near =
	// big/bright, far = small/faint). The icon matches the in-world waypoint marker
	// exactly (same ◆ shape + the waypoint's iconColor); only its SIZE differs,
	// driven by the distance stage and clamped to the bar height so it never clips.
	private static final double[] STAGE_DIST = {60, 200, 500, 1000, 1500, 2000};
	// Stage scale: the gem is drawn at full bar height (5px, touching the bar's
	// borders) up close, then shrinks per stage. Alphas keep a high floor so even
	// the farthest stage stays clearly visible (the old floor of 72/255 at 1px was
	// the "invisible icons" bug).
	private static final float[] STAGE_SCALE = {1.0f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f};
	private static final int[] STAGE_ALPHA = {255, 235, 210, 185, 160, 135};
	// The vanilla empty XP-bar sprite — blitted as the "copy" bar in Creative.
	private static final net.minecraft.resources.ResourceLocation XP_BG =
			net.minecraft.resources.ResourceLocation.withDefaultNamespace("hud/experience_bar_background");

	// The XP bar is exactly 182×5 and sits at (sw-182)/2, sh-29.
	private static final int BAR_W = 182, BAR_H = 5, BAR_Y_FROM_BOTTOM = 29;

	// Non-separate placement: the XP bar's exact HUD slot. In Survival the real XP
	// bar draws it (we only overlay icons); in Creative (no XP bar) we blit a copy
	// of the empty XP-bar sprite in the same spot to host the icons — it shows no XP.
	private static void renderXpHostedBar(GuiGraphics g, Minecraft mc, int sw, int sh) {
		int bx = (sw - BAR_W) / 2;
		int by = sh - BAR_Y_FROM_BOTTOM;
		boolean realXpBar = mc.gameMode != null && mc.gameMode.hasExperience();
		// Survival: real bar present → overlay only. Creative: copy the XP sprite.
		drawBar(g, mc, bx, by, BAR_W, !realXpBar);
	}

	// Draws the locator icons across an XP-bar-sized strip (182×5). When copyXpBar is
	// true it first blits the REAL empty-XP-bar sprite so the strip is a pixel-exact
	// copy of the vanilla bar (used by the movable/separate bar and the Creative host);
	// Survival passes false because the real XP bar is already underneath. Shared by
	// every mode so the icon logic is identical.
	public static void drawBar(GuiGraphics g, Minecraft mc, int bx, int by, int barW, boolean copyXpBar) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		if (copyXpBar) {
			g.blitSprite(XP_BG, bx, by, barW, BAR_H);
		}
		double cxBar = bx + barW / 2.0;
		double cy = by + BAR_H / 2.0;   // gem centre = bar centre; at full scale it spans by..by+5 exactly

		// FOV-cone mapping: the bar spans exactly the player's HORIZONTAL field of
		// view (computed from the vertical FOV option + window aspect). A waypoint
		// dead ahead = bar centre, at the edge of view = bar end; anything OUTSIDE
		// the cone hard-clamps to the exact end (left/right by which side it's on),
		// no matter how far behind it is — 45° past the edge and directly behind
		// land on the same end pixel.
		double vFov = Math.toRadians(mc.options.fov().get());
		double aspect = (double) Math.max(1, mc.getWindow().getWidth()) / Math.max(1, mc.getWindow().getHeight());
		double halfCone = Math.toDegrees(Math.atan(Math.tan(vFov / 2.0) * aspect));

		String dim = mc.level.dimension().location().toString();
		float viewYaw = mc.gameRenderer.getMainCamera().getYRot();
		double px = mc.player.getX(), pz = mc.player.getZ();
		for (Waypoints.Waypoint w : Waypoints.all()) {
			if (!w.enabled || !dim.equals(w.dimension)) {
				continue;
			}
			double dx = (w.x + 0.5) - px, dz = (w.z + 0.5) - pz;
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (dist > 2000.0) {
				continue;             // 2000-block cutoff
			}
			int stage = 0;
			while (stage < 5 && dist > STAGE_DIST[stage]) {
				stage++;
			}
			double wpYaw = Math.toDegrees(Math.atan2(-dx, dz));
			double relYaw = Mth.wrapDegrees(wpYaw - viewYaw);   // -180..180, 0 = ahead
			boolean outsideView = Math.abs(relYaw) > halfCone;
			float s = STAGE_SCALE[stage];
			int alpha = STAGE_ALPHA[stage];
			if (outsideView) {
				// out of view = slightly smaller and dimmer, but with a hard floor so
				// the edge gem is always clearly readable (it's the "turn around" cue).
				s *= 0.85f;
				alpha = Math.max(130, alpha / 2);
			}
			double halfW = 4.5 * s + 1;   // gem half-width incl. border pixel
			double t = outsideView ? Math.signum(relYaw) : relYaw / halfCone;   // -1..1
			double dotX = cxBar + t * (barW / 2.0 - halfW);
			barGem(g, dotX, cy, s, opaque(w.iconColor), alpha);
		}
	}

	// The locator gem: a flattened diamond (9 wide × 5 tall at full scale — exactly
	// the XP bar's height, touching its borders) drawn through a pose scale so every
	// distance stage is a genuinely distinct size. Dark outline for contrast on the
	// green/dark bar + a colour core — the same gem language as the in-world marker,
	// flattened to fit the strip.
	private static void barGem(GuiGraphics g, double cx, double cy, float s, int color, int alpha) {
		int a = Math.max(0, Math.min(255, alpha));
		// The outline is ALWAYS solid black — it's the gem's contrast ring. Fading it
		// with the stage alpha made a white gem on the pale XP sprite literally
		// white-on-white (the "icons invisible on the bar" root cause, pixel-verified).
		int outline = 0xFF000000;
		int fill = (a << 24) | (color & 0xFFFFFF);
		var pose = g.pose();
		pose.pushPose();
		// The local gem is EXACTLY 5 rows tall (9 wide), rows -2..2 with its visual
		// centre at local (0.5, 0.5); this offset puts that centre on (cx, cy), so at
		// s=1 the gem spans the full 5px bar height — border to border, never past.
		pose.translate(cx - 0.5 * s, cy - 0.5 * s, 0);
		pose.scale(s, s, 1f);
		// outline rhombus (widths 1/5/9/5/1)
		g.fill(0, -2, 1, -1, outline);
		g.fill(-2, -1, 3, 0, outline);
		g.fill(-4, 0, 5, 1, outline);
		g.fill(-2, 1, 3, 2, outline);
		g.fill(0, 2, 1, 3, outline);
		// colour core (inset 1: widths 3/7/3) — solid fill, no highlight dot
		g.fill(-1, -1, 2, 0, fill);
		g.fill(-3, 0, 4, 1, fill);
		g.fill(-1, 1, 2, 2, fill);
		pose.popPose();
	}

	// The waypoint marker — a refined diamond gem: a dark outline for contrast on ANY
	// background (so it reads on the green/dark XP bar) and a colour fill. Used for
	// BOTH the in-world icon and the locator dots so they match. R is the OUTER radius
	// incl. the outline; `alpha` scales the whole marker.
	static void marker(GuiGraphics g, int cx, int cy, int r, int color, int alpha) {
		int a = Math.max(0, Math.min(255, alpha));
		int outline = a << 24;                                        // black @ alpha
		int fill = (a << 24) | (color & 0xFFFFFF);
		diamond(g, cx, cy, r, outline);                              // outline ring
		if (r >= 1) {
			diamond(g, cx, cy, r - 1, fill);                        // colour core
		}
	}

	// A filled diamond centered at (cx, cy) with half-size r.
	private static void diamond(GuiGraphics g, int cx, int cy, int r, int color) {
		for (int i = -r; i <= r; i++) {
			int half = r - Math.abs(i);
			g.fill(cx - half, cy + i, cx + half + 1, cy + i + 1, color);
		}
	}

	// Centered text on a background pill, scaled about its top-center anchor.
	// Returns the vertical space consumed (for stacking), in gui pixels.
	private static int pillText(GuiGraphics g, Font font, int cx, int y, String text,
								float scale, int color, int bg) {
		float s = Math.max(0.5f, scale);
		var pose = g.pose();
		pose.pushPose();
		pose.translate(cx, y, 0);
		pose.scale(s, s, 1f);
		int tw = font.width(text);
		if (((bg >>> 24) & 0xFF) > 0) {
			g.fill(-tw / 2 - 2, -2, tw / 2 + 2, 9, bg);
		}
		g.drawString(font, text, -tw / 2, 0, color, false);
		pose.popPose();
		return Math.round(12 * s);
	}

	private static int opaque(int argb) {
		return (argb >>> 24) == 0 ? (0xFF000000 | (argb & 0xFFFFFF)) : argb;
	}
}
