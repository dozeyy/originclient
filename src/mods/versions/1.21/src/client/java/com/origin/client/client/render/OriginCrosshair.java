package com.origin.client.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.HitResult;

// The Crosshair mod: replaces vanilla's crosshair sprite with a shape built
// from the mod's options, drawn from GuiCrosshairMixin (Gui.renderCrosshair).
//
// The shape is rasterised into a small boolean pixel grid in REAL screen
// pixels (the pose is scaled by 1/guiScale while drawing), so a 2-pixel
// crosshair is 2 pixels at every GUI scale. The outline is that grid dilated by
// the outline thickness, so every style -- including the circle and diagonals
// -- gets an exact, even edge. The grid is rebuilt only when an option that
// shapes it changes; per frame it is just a few dozen fills.
public final class OriginCrosshair {
	private OriginCrosshair() {
	}

	private static final ResourceLocation ATTACK_FULL = ResourceLocation.withDefaultNamespace("hud/crosshair_attack_indicator_full");
	private static final ResourceLocation ATTACK_BG = ResourceLocation.withDefaultNamespace("hud/crosshair_attack_indicator_background");
	private static final ResourceLocation ATTACK_PROGRESS = ResourceLocation.withDefaultNamespace("hud/crosshair_attack_indicator_progress");

	private static String cachedKey = null;
	private static boolean[][] fillMask, outlineMask;   // [y][x], centre at (ext, ext)
	private static int ext;

	/**
	 * Draws the custom crosshair (and, in first person, vanilla's attack
	 * indicator, which lives in the same vanilla method we replace).
	 * @return false when vanilla should draw instead (F3 axes view).
	 */
	public static boolean render(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gameMode == null) {
			return true;
		}
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		if (firstPerson && mc.getDebugOverlay().showDebugScreen() && !mc.player.isReducedDebugInfo()
				&& !mc.options.reducedDebugInfo().get()) {
			return false;   // vanilla's 3D debug axes replace any crosshair
		}
		if (!firstPerson && !Mods.bool("crosshair", "showInF5")) {
			return true;
		}
		if (mc.gameMode.getPlayerMode() == GameType.SPECTATOR
				&& (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY)) {
			return true;    // vanilla hides the crosshair in spectator unless aiming at an entity
		}
		ensureMask();
		int color = OriginColorPicker.liveColor("crosshair", "color");
		if (Mods.bool("crosshair", "dynamicColor")) {
			Entity target = mc.crosshairPickEntity;
			if (target instanceof LivingEntity && target.isAlive()) {
				boolean ally = mc.player.isAlliedTo(target);
				boolean hostile = !ally && (target instanceof Enemy || target instanceof Player);
				color = OriginColorPicker.liveColor("crosshair", hostile ? "enemyColor" : "friendlyColor");
			}
		}
		double gs = mc.getWindow().getGuiScale();
		int cx = mc.getWindow().getWidth() / 2;
		int cy = mc.getWindow().getHeight() / 2;
		var p = g.pose();
		p.pushPose();
		p.scale((float) (1.0 / gs), (float) (1.0 / gs), 1f);
		if (Mods.bool("crosshair", "outline")) {
			drawMask(g, outlineMask, cx, cy, OriginColorPicker.liveColor("crosshair", "outlineColor"));
		}
		drawMask(g, fillMask, cx, cy, color);
		p.popPose();

		if (firstPerson) {
			drawAttackIndicator(g, mc);
		}
		return true;
	}

	/** One fill per horizontal run of set pixels. */
	private static void drawMask(GuiGraphics g, boolean[][] mask, int cx, int cy, int color) {
		if (((color >>> 24) & 0xFF) == 0) {
			return;
		}
		int n = mask.length;
		for (int y = 0; y < n; y++) {
			boolean[] row = mask[y];
			int x = 0;
			while (x < n) {
				if (!row[x]) {
					x++;
					continue;
				}
				int start = x;
				while (x < n && row[x]) {
					x++;
				}
				g.fill(cx + start - ext, cy + y - ext, cx + x - ext, cy + y - ext + 1, color);
			}
		}
	}

	// ---- shape rasterisation ----

	private static void ensureMask() {
		String style = Mods.mode("crosshair", "style");
		int s = (int) Mods.num("crosshair", "size");
		int t = Math.max(1, (int) Mods.num("crosshair", "thickness"));
		int gap = (int) Mods.num("crosshair", "gap");
		boolean dot = Mods.bool("crosshair", "dot");
		int dotSize = Math.max(1, (int) Mods.num("crosshair", "dotSize"));
		int o = Mods.bool("crosshair", "outline") ? Math.max(1, (int) Mods.num("crosshair", "outlineThickness")) : 0;
		String key = style + "|" + s + "|" + t + "|" + gap + "|" + dot + "|" + dotSize + "|" + o;
		if (key.equals(cachedKey)) {
			return;
		}
		cachedKey = key;
		ext = gap + s + t + dotSize + o + 3;
		int n = ext * 2 + 1;
		boolean[][] fill = new boolean[n][n];
		int t0 = -(t - 1) / 2, t1 = t0 + t - 1;          // the arm's cross-section around pixel 0
		int d0 = -(dotSize - 1) / 2, d1 = d0 + dotSize - 1;
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				int dx = x - ext, dy = y - ext;
				boolean on = switch (style) {
					case "Dot" -> dx * dx + dy * dy <= (s / 2.0 + 0.25) * (s / 2.0 + 0.25);
					case "Circle" -> {
						double d = Math.sqrt(dx * dx + dy * dy);
						double r = gap + s;
						yield d >= r - t / 2.0 && d < r + t / 2.0;
					}
					case "Square" -> {
						int m = Math.max(Math.abs(dx), Math.abs(dy));
						int r = gap + s;
						yield m <= r && m > r - t;
					}
					case "Arrow" -> {
						int k = dy - gap;                      // apex sits `gap` below centre
						int off = Math.abs(dx) - k;              // thickens outward from the line
						yield k >= 0 && k <= s && off >= 0 && off <= t - 1;
					}
					case "X" -> {
						int a = Math.abs(dx), b = Math.abs(dy);
						int m = Math.max(a, b);
						int off = a - b;                         // thickens toward the horizontal
						yield off >= 0 && off <= t - 1 && m > gap && m <= gap + s;
					}
					default -> {                               // "Cross" and "T"
						boolean vert = dx >= t0 && dx <= t1
								&& ((dy >= t0 - gap - s && dy <= t0 - gap - 1 && !style.equals("T"))
								|| (dy >= t1 + gap + 1 && dy <= t1 + gap + s));
						boolean horiz = dy >= t0 && dy <= t1
								&& ((dx >= t0 - gap - s && dx <= t0 - gap - 1)
								|| (dx >= t1 + gap + 1 && dx <= t1 + gap + s));
						yield vert || horiz;
					}
				};
				if (dot && dx >= d0 && dx <= d1 && dy >= d0 && dy <= d1) {
					on = true;
				}
				fill[y][x] = on;
			}
		}
		boolean[][] outline = new boolean[n][n];
		if (o > 0) {
			for (int y = 0; y < n; y++) {
				for (int x = 0; x < n; x++) {
					if (fill[y][x]) {
						continue;
					}
					search:
					for (int oy = -o; oy <= o; oy++) {
						for (int ox = -o; ox <= o; ox++) {
							int yy = y + oy, xx = x + ox;
							if (yy >= 0 && yy < n && xx >= 0 && xx < n && fill[yy][xx]) {
								outline[y][x] = true;
								break search;
							}
						}
					}
				}
			}
		}
		fillMask = fill;
		outlineMask = outline;
	}

	// ---- vanilla's attack indicator, unchanged (it lives in the replaced method) ----

	private static void drawAttackIndicator(GuiGraphics g, Minecraft mc) {
		if (mc.options.attackIndicator().get() != AttackIndicatorStatus.CROSSHAIR) {
			return;
		}
		float strength = mc.player.getAttackStrengthScale(0.0F);
		boolean full = false;
		if (mc.crosshairPickEntity instanceof LivingEntity && strength >= 1.0F) {
			full = mc.player.getCurrentItemAttackStrengthDelay() > 5.0F && mc.crosshairPickEntity.isAlive();
		}
		int y = g.guiHeight() / 2 - 7 + 16;
		int x = g.guiWidth() / 2 - 8;
		RenderSystem.enableBlend();
		RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
				GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
		if (full) {
			g.blitSprite(ATTACK_FULL, x, y, 16, 16);
		} else if (strength < 1.0F) {
			int w = (int) (strength * 17.0F);
			g.blitSprite(ATTACK_BG, x, y, 16, 4);
			g.blitSprite(ATTACK_PROGRESS, 16, 4, 0, 0, x, y, w, 4);
		}
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
	}
}
