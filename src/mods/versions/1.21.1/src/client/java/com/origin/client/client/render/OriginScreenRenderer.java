package com.origin.client.client.render;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;

// Shared Origin screen rendering, used by both the loading screen
// (LoadingOverlayMixin) and the main menu (TitleScreenMixin): near-black
// (#050505) background, the pre-rendered crisp orbital rings (mirroring the
// launcher's OriginBackground), fine grain, and the "Origin" wordmark in the
// website's Inter font (baked to a texture so it shows instantly and carries no
// custom-glyph-rendering risk). Matches the original mockup
// (tools/loading-screen/wordmark_preview.png).
//
// Textures load via the classloader (not the resource manager), so this is
// safe during the earliest loading overlay while resources are still loading,
// and degrades gracefully if any asset fails rather than crashing.
public final class OriginScreenRenderer {
	private static final Gson GSON = new Gson();
	private static final int TEX = 768;
	private static final int BG_COLOR = OriginTheme.BG;

	// Fail-soft master switch: if ANY Origin screen draw throws (e.g. a
	// Minecraft GUI API that renamed/changed shape in a different game
	// version), rendering flips to vanilla permanently for this session
	// instead of crashing. Callers that cancel vanilla drawing must only
	// cancel when the boolean-returning entry points report success, and
	// must gate standalone suppressions on isActive(). This is the runtime
	// half of the multi-version contract (see VERSIONS.md); the mixin
	// configs' required:false is the load-time half.
	private static volatile boolean broken = false;

	private static volatile boolean loaded = false;
	private static boolean ringsFailed = false;
	private static final List<Ring> rings = new ArrayList<>();
	private static ResourceLocation grainId;

	// Edge vignette (transparent core -> soft black corners). Blitted full-screen
	// to darken the frame edges so the centered wordmark reads as the focal
	// point -- depth from lightness only, no hue. Null -> skip (no crash).
	private static ResourceLocation vignetteId;
	private static final int VIGNETTE_TEX = 1024;

	// Baked "ORIGIN" wordmark (Michroma, all-caps + glow bloom). Null -> fall
	// back to vanilla drawString.
	private static ResourceLocation wordmarkId;
	private static int wmTexW, wmTexH, wmInkX, wmInkY, wmInkW, wmInkH;
	// Per-letter reveal bands: [x0,x1] texture-px columns, one per glyph, from
	// wordmark.json. Null -> the loading screen draws the whole mark at once
	// (no staggered reveal) instead of failing.
	private static int[] wmLetterX0, wmLetterX1;

	// Loading-screen animation clock: millis at the first loading frame, so the
	// per-letter reveal and the caption's cycling dots are time-driven.
	private static long loadStartMs = 0L;

	// ---- Main-menu ambient layer ----
	// Ellipse height:width of the ring textures (generate_textures HEIGHT_RATIO),
	// needed to place a body on a ring's path.
	private static final double RING_HEIGHT_RATIO = 0.37;
	// Wordmark breathing-glow period (ms) on the title screen.
	private static final double BREATH_MS = 3200.0;
	// Orbiting bodies riding the rings: {ringIndex, periodSeconds, phase, coreFrac(of w), alpha}.
	// One bright body on the front ring, one fainter/slower on a back ring.
	private static final double[][] BODIES = {
			{0, 26.0, 0.0, 0.010, 0.85},
			{2, 40.0, 0.5, 0.008, 0.55},
	};
	// Sparse drifting background dust.
	private static final int PARTICLE_COUNT = 28;

	// Cursor-follow glow (the website's two-layer spotlight): one radial
	// gradient texture drawn twice -- a tight core that snaps to the cursor
	// and a soft halo that trails it with lerped physics, both blooming when
	// hovering a clickable. State is static: one cursor, one glow.
	private static ResourceLocation radialGlowId;
	private static final int RADIAL_TEX = 512;
	private static double haloX = Double.NaN, haloY = Double.NaN;
	private static double glowHover = 0.0;
	private static long glowLastNanos = 0L;

	private record Ring(ResourceLocation texture, double widthFrac, float opacity,
						double angle0, double periodSeconds, boolean reverse) {
	}

	private OriginScreenRenderer() {
	}

	/** False once any Origin screen draw has failed; vanilla takes over. */
	public static boolean isActive() {
		return !broken;
	}

	private static boolean fail(Throwable t) {
		broken = true;
		com.origin.client.OriginClient.LOGGER.error(
				"Origin screen rendering failed; falling back to vanilla screens for this session", t);
		return false;
	}

	// ---- Public entry points ----

	/**
	 * Startup resource-load screen — the original mockup
	 * (tools/loading-screen/wordmark_preview.png): near-black + crisp orbital
	 * rings + fine grain + the "Origin" wordmark with its glow bloom, and a thin
	 * word-width progress bar sitting just beneath it (the mock's underline).
	 * No percentage caption: the mock has none, and the clean logo-over-bar
	 * reads more premium.
	 */
	public static void renderLoading(GuiGraphics guiGraphics, float progress) {
		// Drawn at TAIL over vanilla's overlay, so skipping when broken simply
		// reveals the vanilla loading screen -- no cancel to unwind.
		if (broken) {
			return;
		}
		try {
			renderLoading0(guiGraphics, progress);
		} catch (Throwable t) {
			fail(t);
		}
	}

	private static void renderLoading0(GuiGraphics guiGraphics, float progress) {
		ensureLoaded();
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		float clamped = Math.max(0f, Math.min(1f, progress));

		if (loadStartMs == 0L) {
			loadStartMs = System.currentTimeMillis();
		}
		long elapsed = System.currentTimeMillis() - loadStartMs;

		guiGraphics.fill(0, 0, w, h, BG_COLOR);
		if (!ringsFailed) {
			drawRings(guiGraphics, w, h);
			drawGrain(guiGraphics, w, h);
		}
		// Vignette above the rings/grain, under the wordmark: darkens the edges
		// so the mark is the focal point.
		drawVignette(guiGraphics, w, h);

		// Wordmark nudged slightly above center so the logo+bar group stays
		// balanced. All-caps "ORIGIN" has no descender, so its ink height IS the
		// cap height: 0.135h gives the right cap size (Will's chosen "middle"
		// size). The bar sits well below the logo with a clear gap and is a
		// chunky full-word-width bar (Will: "farther down and bigger"). All
		// verified in-sandbox against the ring background. On the startup screen
		// the mark builds in letter by letter (staggered fade + rise).
		double markInkH = fitInkHeight(h * 0.135, w, 0.82);
		double markCenterY = h * 0.48;
		drawWordmarkReveal(guiGraphics, w / 2.0, markCenterY, markInkH, elapsed);

		double dispW = (wordmarkId != null && wmInkH > 0) ? wmInkW * (markInkH / wmInkH) : w * 0.24;
		int barW = (int) Math.round(dispW);
		int barH = Math.max(3, (int) Math.round(h * 0.012));
		int barTop = (int) Math.round(markCenterY + markInkH * 1.15);
		drawBar(guiGraphics, w / 2.0, barTop, barW, barH, clamped);

		// No text on this screen: it renders DURING the initial resource reload,
		// before any font glyphs are baked, so font-drawn strings can only ever
		// show missing-glyph boxes here (vanilla's overlay avoids text for the
		// same reason). The animated wordmark + bar carry the scene — which is
		// also the original mock's look. Corner brackets on top of everything.
		drawCornerBrackets(guiGraphics, w, h);
	}

	/**
	 * Full-screen loading/progress scene for the world-load screens
	 * (LevelLoadingScreen, ReceivingLevelScreen, ProgressScreen): the same
	 * charcoal + rings + grain background as the menus, the screen's title in
	 * the default font, and a smooth indeterminate bar. Drawn as a HEAD-cancel
	 * takeover, so it replaces vanilla's chunk map / dirt / progress clutter
	 * with one clean Origin scene. Indeterminate (not tied to a real percent):
	 * reading each screen's internal progress field would need an unverifiable
	 * @Shadow, and a calm sweeping bar reads as premium regardless.
	 */
	public static boolean renderLoadingScene(GuiGraphics guiGraphics, net.minecraft.network.chat.Component title) {
		// HEAD-cancel takeover: callers must only ci.cancel() when this
		// returns true, so a failure mid-draw falls back to the vanilla screen.
		if (broken) {
			return false;
		}
		try {
			renderLoadingScene0(guiGraphics, title);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	private static void renderLoadingScene0(GuiGraphics guiGraphics, net.minecraft.network.chat.Component title) {
		ensureLoaded();
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();

		guiGraphics.fill(0, 0, w, h, BG_COLOR);
		if (!ringsFailed) {
			drawRings(guiGraphics, w, h);
			drawGrain(guiGraphics, w, h);
		}
		drawFrame(guiGraphics, w, h);

		Font font = mc.font;
		int barW = Math.max(80, (int) (w * 0.30));
		int barH = Math.max(2, (int) (h * 0.010));
		float titleScale = 1.5f;
		int titleH = (int) Math.ceil(font.lineHeight * titleScale);
		double gap = Math.max(8.0, h * 0.045);

		String s = title == null ? "" : title.getString().trim();
		boolean hasTitle = !s.isEmpty();
		double groupH = (hasTitle ? titleH + gap : 0) + barH;
		double groupTop = h / 2.0 - groupH / 2.0;

		if (hasTitle) {
			PoseStack pose = guiGraphics.pose();
			pose.pushPose();
			pose.translate(w / 2.0, groupTop, 0);
			pose.scale(titleScale, titleScale, 1f);
			guiGraphics.drawString(font, s, -font.width(s) / 2, 0, OriginTheme.TEXT, false);
			pose.popPose();
		}
		int barTop = (int) Math.round(groupTop + (hasTitle ? titleH + gap : 0));
		drawIndeterminateBar(guiGraphics, w / 2.0, barTop, barW, barH);
	}

	/**
	 * Just the indeterminate bar, placed just below screen center -- for
	 * screens kept on their vanilla render (ConnectScreen: its Cancel button
	 * and status text stay, the Origin menu background comes from
	 * ScreenBackgroundMixin, and this adds the bar on top).
	 */
	public static void renderConnectingBar(GuiGraphics guiGraphics) {
		// TAIL-additive over the vanilla connect screen; skip when broken.
		if (broken) {
			return;
		}
		try {
			ensureLoaded();
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			int barW = Math.max(80, (int) (w * 0.30));
			int barH = Math.max(2, (int) (h * 0.010));
			drawIndeterminateBar(guiGraphics, w / 2.0, (int) (h * 0.60), barW, barH);
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** A calm sweeping segment (smooth ping-pong) over the standard track. */
	private static void drawIndeterminateBar(GuiGraphics guiGraphics, double cx, int barTop, int barW, int barH) {
		int bx = (int) Math.round(cx - barW / 2.0);
		int by = barTop;
		guiGraphics.fill(bx, by, bx + barW, by + barH, 0x29FFFFFF); // track
		int segW = Math.max(8, (int) (barW * 0.32));
		int travel = Math.max(1, barW - segW);
		double t = (System.currentTimeMillis() % 1800L) / 1800.0;
		double eased = 0.5 - 0.5 * Math.cos(t * 2.0 * Math.PI); // smooth 0->1->0
		int segX = bx + (int) Math.round(eased * travel);
		guiGraphics.fill(segX - 1, by - 1, segX + segW + 1, by + barH + 1, OriginTheme.ACCENT_GLOW);
		guiGraphics.fill(segX, by, segX + segW, by + barH, OriginTheme.ACCENT);
	}

	/**
	 * Menu background: near-black + rotating rings + grain (behind vanilla's
	 * logo/buttons). Returns true only if the Origin backdrop actually drew;
	 * callers that cancel vanilla's own backdrop must key off this.
	 */
	public static boolean renderTitleBackground(GuiGraphics guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();

			guiGraphics.fill(0, 0, w, h, BG_COLOR);
			if (!ringsFailed) {
				drawRings(guiGraphics, w, h);
				drawGrain(guiGraphics, w, h);
			}
			// Menu ambient life: drifting dust behind, bodies orbiting the rings
			// on top -- both under the vignette so the frame still darkens them.
			drawParticles(guiGraphics, w, h);
			drawOrbitingBodies(guiGraphics, w, h);
			drawFrame(guiGraphics, w, h);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/**
	 * Main menu: the website's two-layer cursor spotlight, drawn over the ring
	 * background but under the widgets/wordmark. The core (small, brighter)
	 * snaps to the cursor every frame; the halo (large, faint) trails it via
	 * the website's exact per-frame lerp (0.12 @60fps, dt-corrected here so it
	 * feels identical at any framerate). Both grow + brighten while hovering a
	 * clickable, mirroring the site's .is-active bloom. Sizes scale with the
	 * GUI width the way the CSS pixel sizes relate to a typical viewport.
	 */
	public static void renderTitleCursorGlow(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hoveringClickable) {
		// Purely additive; skip when broken.
		if (broken) {
			return;
		}
		try {
			renderTitleCursorGlow0(guiGraphics, mouseX, mouseY, hoveringClickable);
		} catch (Throwable t) {
			fail(t);
		}
	}

	private static void renderTitleCursorGlow0(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hoveringClickable) {
		ensureLoaded();
		if (radialGlowId == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();

		long now = System.nanoTime();
		double dtMs = glowLastNanos == 0 ? 16.7 : Math.min(100.0, (now - glowLastNanos) / 1_000_000.0);
		glowLastNanos = now;

		// Hover bloom easing (~0.3s ease, like the site's transition).
		double target = hoveringClickable ? 1.0 : 0.0;
		double step = dtMs / 250.0;
		glowHover = target > glowHover ? Math.min(target, glowHover + step) : Math.max(target, glowHover - step);
		double hv = OriginTheme.easeOut(glowHover);

		// Halo lag, dt-corrected. The website's 0.12/frame felt too floaty
		// in-game (Will: "much faster, just a slight lag") -- 0.38/frame keeps
		// a visible trail but snaps close behind the cursor.
		if (Double.isNaN(haloX)) {
			haloX = mouseX;
			haloY = mouseY;
		}
		double f = 1.0 - Math.pow(1.0 - 0.38, dtMs / 16.7);
		haloX += (mouseX - haloX) * f;
		haloY += (mouseY - haloY) * f;

		RenderSystem.enableBlend();
		// Sizes are ~40% of the website's proportional values -- the 1:1
		// translation read far too big in-game (Will: "shrink by 60% at least").
		drawRadial(guiGraphics, haloX, haloY, w * (0.14 + 0.04 * hv), 0.112 + 0.063 * hv);
		drawRadial(guiGraphics, mouseX, mouseY, w * (0.032 + 0.018 * hv), 0.30 + 0.17 * hv);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	private static void drawRadial(GuiGraphics guiGraphics, double cx, double cy, double diameter, double alpha) {
		int d = Math.max(2, (int) Math.round(diameter));
		RenderSystem.setShaderColor(1f, 1f, 1f, (float) alpha);
		guiGraphics.blit(radialGlowId, (int) Math.round(cx - d / 2.0), (int) Math.round(cy - d / 2.0),
				d, d, 0f, 0f, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX);
	}

	/**
	 * Main menu: draw the "ORIGIN" wordmark centered between the top of the
	 * screen and the Singleplayer button. Returns true only if it drew;
	 * the title logo redirect falls back to vanilla's logo on false.
	 */
	public static boolean renderTitleWordmark(GuiGraphics guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();

			int singleplayerTop = h / 4 + 48;        // vanilla TitleScreen first-button Y
			double centerY = singleplayerTop / 2.0;  // midpoint between top of screen and that button
			double inkH = fitInkHeight(h * 0.13, w, 0.82); // same size as the loading screen
			// Breathing glow: a faint, slightly-larger extra pass whose alpha
			// swells on a slow sine, so the bloom pulses under the crisp mark.
			double pulse = 0.5 - 0.5 * Math.cos(System.currentTimeMillis() / BREATH_MS * 2.0 * Math.PI);
			drawWordmarkGlow(guiGraphics, w / 2.0, centerY, inkH, 1.06, (float) (0.05 + 0.10 * pulse));
			drawWordmark(guiGraphics, w / 2.0, centerY, inkH);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/**
	 * Draw the baked "ORIGIN" wordmark centered on (cx, cy) at the given ink
	 * height, anywhere. Used by the HUD editor's Right-Shift header. Returns
	 * false (caller falls back to the vanilla font) if the texture is missing or
	 * Origin rendering has failed for the session.
	 */
	public static boolean renderWordmarkAt(GuiGraphics guiGraphics, double cx, double cy, double targetInkHeight, float alpha) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			if (wordmarkId == null || wmInkH <= 0) {
				return false;
			}
			drawBakedInk(guiGraphics, wordmarkId, wmTexW, wmTexH, wmInkX, wmInkY, wmInkW, wmInkH, cx, cy, targetInkHeight, alpha);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/** Shared: blit a baked-text texture so its ink box centers on (cx, cy), ink scaled to targetInkHeight. */
	private static void drawBakedInk(GuiGraphics guiGraphics, ResourceLocation tex, int texW, int texH,
									 int inkX, int inkY, int inkW, int inkH, double cx, double cy, double targetInkHeight, float alpha) {
		float scale = (float) (targetInkHeight / inkH);
		double icx = (inkX + inkW / 2.0) * scale;
		double icy = (inkY + inkH / 2.0) * scale;
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(cx - icx, cy - icy, 0);
		pose.scale(scale, scale, 1f);
		RenderSystem.enableBlend();
		RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
		guiGraphics.blit(tex, 0, 0, 0, 0, texW, texH, texW, texH);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		pose.popPose();
	}

	/**
	 * Main menu: a small account chip (player head + username) in the top-left,
	 * inside the frame. Identity presence, like premium launchers. Head via the
	 * skin manager (default skin shows first, the real one pops in async); if the
	 * head can't be resolved the chip falls back to the Origin ring mark, and if
	 * the whole chip throws it flips the renderer to vanilla like any Origin draw.
	 */
	public static void renderTitleAccountChip(GuiGraphics guiGraphics) {
		if (broken) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			net.minecraft.client.User user = mc.getUser();
			if (user == null) {
				return;
			}
			String name = user.getName();
			if (name == null || name.isEmpty()) {
				return;
			}
			Font font = mc.font;
			int w = mc.getWindow().getGuiScaledWidth();
			int head = 18;
			int padX = 8, padY = 6, gap = 8;
			int chipH = head + padY * 2;
			int chipW = padX + head + gap + font.width(name) + padX;
			int x = Math.max(10, (int) Math.round(w * 0.03));
			int y = x;

			// No border stroke (Will) — just a faint translucent backing so the
			// white username stays legible over the rings.
			OriginUi.panel(guiGraphics, x, y, chipW, chipH, OriginTheme.RADIUS_MD,
					OriginTheme.PANEL_TRANSLUCENT, 0);

			int hx = x + padX, hy = y + padY;
			boolean drewHead = false;
			try {
				com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(user.getProfileId(), name);
				net.minecraft.client.resources.PlayerSkin skin = mc.getSkinManager().getInsecureSkin(profile);
				net.minecraft.client.gui.components.PlayerFaceRenderer.draw(guiGraphics, skin, hx, hy, head);
				drewHead = true;
			} catch (Throwable ignored) {
				// fall back to the ring mark below
			}
			if (!drewHead) {
				OriginUi.logo(guiGraphics, hx + head / 2.0, hy + head / 2.0, head, 1f);
			}

			guiGraphics.drawString(font, name, hx + head + gap,
					y + (chipH - font.lineHeight) / 2, OriginTheme.TEXT, false);
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** Clamps a target ink height so the wordmark's displayed width stays within maxWidthFrac of the screen. */
	private static double fitInkHeight(double targetInkHeight, int w, double maxWidthFrac) {
		if (wordmarkId == null || wmInkW <= 0) {
			return targetInkHeight;
		}
		double dispW = wmInkW * (targetInkHeight / wmInkH);
		double maxW = w * maxWidthFrac;
		if (dispW > maxW) {
			return wmInkH * (maxW / wmInkW);
		}
		return targetInkHeight;
	}

	// ---- Primitives ----

	private static void drawRings(GuiGraphics guiGraphics, int w, int h) {
		double cx = w / 2.0;
		double cy = h / 2.0;
		long now = System.currentTimeMillis();
		PoseStack pose = guiGraphics.pose();

		RenderSystem.enableBlend();
		for (Ring ring : rings) {
			double revs = (now / 1000.0) / ring.periodSeconds();
			// Wrap to [0,360) in double BEFORE the float cast: the absolute
			// angle grows to ~1e10 degrees over time, which a 32-bit float
			// quantizes to ~1000-degree steps -> rings freeze then jump every
			// several seconds. Modulo keeps it small and precise.
			double angle = (ring.angle0() + (ring.reverse() ? -revs : revs) * 360.0) % 360.0;
			float scale = (float) (ring.widthFrac() * w * 1.1 / TEX); // *1.1: ellipse fills 0.9 of its square texture

			pose.pushPose();
			pose.translate(cx, cy, 0);
			pose.mulPose(Axis.ZP.rotationDegrees((float) angle));
			pose.scale(scale, scale, 1f);
			pose.translate(-TEX / 2f, -TEX / 2f, 0);
			RenderSystem.setShaderColor(1f, 1f, 1f, ring.opacity());
			guiGraphics.blit(ring.texture(), 0, 0, 0, 0, TEX, TEX, TEX, TEX);
			pose.popPose();
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	private static void drawGrain(GuiGraphics guiGraphics, int w, int h) {
		if (grainId == null) {
			return;
		}
		// Tile in REAL pixels (pose-scale 1/guiScale): drawn in GUI units the
		// 1px noise texels become guiScale-sized blocks -- the "low res" grain
		// Will flagged. At 1:1 each noise grain is a single screen pixel,
		// like the website's.
		double gs = Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
		int realW = (int) Math.ceil(w * gs);
		int realH = (int) Math.ceil(h * gs);
		int tile = 256; // matches grain.png; larger tile -> ~3x fewer blits/frame
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.scale((float) (1.0 / gs), (float) (1.0 / gs), 1f);
		RenderSystem.enableBlend();
		RenderSystem.setShaderColor(1f, 1f, 1f, 0.028f);
		for (int y = 0; y < realH; y += tile) {
			for (int x = 0; x < realW; x += tile) {
				guiGraphics.blit(grainId, x, y, 0, 0, tile, tile, tile, tile);
			}
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		pose.popPose();
	}

	private static double frac(double v) {
		return v - Math.floor(v);
	}

	/**
	 * Sparse drifting background dust: fixed pseudo-random particles (seeded from
	 * the index, no stored state) that slowly drift and twinkle. Monochrome, very
	 * low alpha -- depth and life without pulling focus.
	 */
	private static void drawParticles(GuiGraphics guiGraphics, int w, int h) {
		double t = System.currentTimeMillis() / 1000.0;
		for (int i = 0; i < PARTICLE_COUNT; i++) {
			double h1 = frac(Math.sin(i * 12.9898) * 43758.5453);
			double h2 = frac(Math.sin(i * 78.233) * 12345.678);
			double h3 = frac(Math.sin(i * 39.425) * 98765.43);
			double speed = 0.004 + 0.010 * h3;
			double dir = (i % 2 == 0) ? 1.0 : -1.0;
			double x = frac(h1 + t * speed * 0.15 * dir) * w;
			double y = frac(h2 - t * speed * 0.10) * h;
			double twinkle = 0.5 - 0.5 * Math.cos(t * (0.4 + 0.6 * h3) + h1 * 6.2832);
			int a = (int) Math.round((0.04 + 0.09 * h3) * twinkle * 255);
			if (a <= 1) {
				continue;
			}
			int px = (int) Math.round(x), py = (int) Math.round(y);
			int sz = h3 > 0.7 ? 2 : 1;
			guiGraphics.fill(px, py, px + sz, py + sz, (a << 24) | 0x00FFFFFF);
		}
	}

	/**
	 * Bodies orbiting the rings: each body's parametric point on a ring's ellipse
	 * (a, a*heightRatio) advances on its own period, then is rotated by that
	 * ring's live rotation so it visually rides the tilted ellipse -- a small
	 * glowing "planet on its orbit" that plays the Origin identity. Drawn with the
	 * radial-glow texture (halo + core). Skipped if the rings/glow aren't loaded.
	 */
	private static void drawOrbitingBodies(GuiGraphics guiGraphics, int w, int h) {
		if (ringsFailed || rings.isEmpty() || radialGlowId == null) {
			return;
		}
		double cx = w / 2.0, cy = h / 2.0;
		double now = System.currentTimeMillis() / 1000.0;
		RenderSystem.enableBlend();
		for (double[] body : BODIES) {
			int idx = (int) body[0];
			if (idx < 0 || idx >= rings.size()) {
				continue;
			}
			Ring ring = rings.get(idx);
			double a = ring.widthFrac() * w * 0.99 / 2.0;
			double b = a * RING_HEIGHT_RATIO;
			double revs = now / ring.periodSeconds();
			double ringAngle = (ring.angle0() + (ring.reverse() ? -revs : revs) * 360.0) % 360.0;
			double rad = Math.toRadians(ringAngle);
			double phi = (now / body[1] + body[2]) * 2.0 * Math.PI;
			double lx = a * Math.cos(phi), ly = b * Math.sin(phi);
			double px = cx + lx * Math.cos(rad) - ly * Math.sin(rad);
			double py = cy + lx * Math.sin(rad) + ly * Math.cos(rad);
			double core = w * body[3];
			drawRadial(guiGraphics, px, py, core * 3.2, body[4] * 0.28); // halo
			drawRadial(guiGraphics, px, py, core, body[4]);              // core
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	/** Extra wordmark pass at a scale multiplier and alpha -- used for the breathing glow. */
	private static void drawWordmarkGlow(GuiGraphics guiGraphics, double inkCenterX, double inkCenterY,
										 double targetInkHeight, double scaleMul, float alpha) {
		if (wordmarkId == null || wmInkH <= 0 || alpha <= 0.001f) {
			return;
		}
		float scale = (float) (targetInkHeight / wmInkH * scaleMul);
		double icx = (wmInkX + wmInkW / 2.0) * scale;
		double icy = (wmInkY + wmInkH / 2.0) * scale;
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(inkCenterX - icx, inkCenterY - icy, 0);
		pose.scale(scale, scale, 1f);
		RenderSystem.enableBlend();
		RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
		guiGraphics.blit(wordmarkId, 0, 0, 0, 0, wmTexW, wmTexH, wmTexW, wmTexH);
		pose.popPose();
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	/** Shared frame dressing for the ring backdrop: edge vignette + corner brackets. */
	private static void drawFrame(GuiGraphics guiGraphics, int w, int h) {
		drawVignette(guiGraphics, w, h);
		drawCornerBrackets(guiGraphics, w, h);
	}

	/** Full-screen edge vignette (the 1024px texture stretched to the GUI size). */
	private static void drawVignette(GuiGraphics guiGraphics, int w, int h) {
		if (vignetteId == null) {
			return;
		}
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.scale((float) w / VIGNETTE_TEX, (float) h / VIGNETTE_TEX, 1f);
		RenderSystem.enableBlend();
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		guiGraphics.blit(vignetteId, 0, 0, 0f, 0f, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX);
		pose.popPose();
	}

	/**
	 * Thin L-brackets inset from each screen corner — an aerospace/instrument
	 * framing cue that matches the Michroma wordmark and adds "designed" edges
	 * without a hue. Drawn in the soft-strong stroke tone so they read as quiet
	 * registration marks, not a hard border.
	 */
	private static void drawCornerBrackets(GuiGraphics guiGraphics, int w, int h) {
		int inset = Math.max(10, (int) Math.round(w * 0.022));
		int len = Math.max(10, (int) Math.round(w * 0.018));
		int th = Math.max(1, (int) Math.round(w * 0.0015));
		int c = OriginTheme.STROKE_STRONG;
		// top-left
		guiGraphics.fill(inset, inset, inset + len, inset + th, c);
		guiGraphics.fill(inset, inset, inset + th, inset + len, c);
		// top-right
		guiGraphics.fill(w - inset - len, inset, w - inset, inset + th, c);
		guiGraphics.fill(w - inset - th, inset, w - inset, inset + len, c);
		// bottom-left
		guiGraphics.fill(inset, h - inset - th, inset + len, h - inset, c);
		guiGraphics.fill(inset, h - inset - len, inset + th, h - inset, c);
		// bottom-right
		guiGraphics.fill(w - inset - len, h - inset - th, w - inset, h - inset, c);
		guiGraphics.fill(w - inset - th, h - inset - len, w - inset, h - inset, c);
	}

	/**
	 * Wordmark with a per-letter staggered reveal: each glyph band (from
	 * wordmark.json) fades in and rises into place, offset by index, so the mark
	 * assembles left-to-right instead of popping in whole. Once every letter has
	 * finished (alpha 1, no offset) the bands tile seamlessly back into the full
	 * wordmark. Falls back to the plain whole-mark draw if the bands are missing.
	 */
	private static void drawWordmarkReveal(GuiGraphics guiGraphics, double inkCenterX, double inkCenterY,
										   double targetInkHeight, long elapsedMs) {
		if (wordmarkId == null || wmLetterX0 == null || wmInkH <= 0) {
			drawWordmark(guiGraphics, inkCenterX, inkCenterY, targetInkHeight);
			return;
		}
		float scale = (float) (targetInkHeight / wmInkH);
		double icx = (wmInkX + wmInkW / 2.0) * scale;
		double icy = (wmInkY + wmInkH / 2.0) * scale;
		double rise = wmInkH * 0.10;   // texture-px the letters drop in from
		double stagger = 55.0;         // ms between successive letters
		double dur = 300.0;            // ms per-letter fade/rise

		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(inkCenterX - icx, inkCenterY - icy, 0);
		pose.scale(scale, scale, 1f);
		RenderSystem.enableBlend();
		for (int i = 0; i < wmLetterX0.length; i++) {
			double lt = (elapsedMs - i * stagger) / dur;
			lt = Math.max(0.0, Math.min(1.0, lt));
			double eased = OriginTheme.easeOut(lt);
			if (eased <= 0.001) {
				continue;
			}
			int x0 = wmLetterX0[i];
			int bw = wmLetterX1[i] - x0;
			if (bw <= 0) {
				continue;
			}
			int yoff = (int) Math.round((1.0 - eased) * rise);
			RenderSystem.setShaderColor(1f, 1f, 1f, (float) eased);
			guiGraphics.blit(wordmarkId, x0, yoff, (float) x0, 0f, bw, wmTexH, wmTexW, wmTexH);
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		pose.popPose();
	}

	/** Draws the wordmark with its ink box centered on (inkCenterX, inkCenterY), ink scaled to targetInkHeight. Returns ink bottom (screen Y). */
	private static int drawWordmark(GuiGraphics guiGraphics, double inkCenterX, double inkCenterY, double targetInkHeight) {
		if (wordmarkId != null) {
			float scale = (float) (targetInkHeight / wmInkH);
			double icx = (wmInkX + wmInkW / 2.0) * scale;
			double icy = (wmInkY + wmInkH / 2.0) * scale;

			PoseStack pose = guiGraphics.pose();
			pose.pushPose();
			pose.translate(inkCenterX - icx, inkCenterY - icy, 0);
			pose.scale(scale, scale, 1f);
			RenderSystem.enableBlend();
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			guiGraphics.blit(wordmarkId, 0, 0, 0, 0, wmTexW, wmTexH, wmTexW, wmTexH);
			pose.popPose();

			return (int) Math.round(inkCenterY + (wmInkH * scale) / 2.0);
		}

		// Fallback (texture missing): vanilla font, centered on the point.
		Font font = Minecraft.getInstance().font;
		String mark = "ORIGIN";
		float scale = 4.0f;
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(inkCenterX, inkCenterY, 0);
		pose.scale(scale, scale, 1f);
		int textW = font.width(mark);
		guiGraphics.drawString(font, mark, -textW / 2, -4, OriginTheme.TEXT, false);
		pose.popPose();
		return (int) Math.round(inkCenterY + 5 * scale);
	}

	/** Draws the progress bar centered on cx at barTop, with the given width/height. */
	private static void drawBar(GuiGraphics guiGraphics, double cx, int barTop, int barW, int barH, float progress) {
		int bx = (int) Math.round(cx - barW / 2.0);
		int by = barTop;

		// Track brighter than the hairline stroke: at 8% white on the near-black
		// background the unfilled track was invisible in-game, so only the fill
		// showed and the bar read as the wrong (too small) size. ~16% matches how
		// the mockup's track actually reads on screen.
		guiGraphics.fill(bx, by, bx + barW, by + barH, 0x29FFFFFF);              // track
		int fillW = Math.round(barW * progress);
		if (fillW > 0) {
			guiGraphics.fill(bx - 1, by - 1, bx + fillW + 1, by + barH + 1, OriginTheme.ACCENT_GLOW); // soft glow
			guiGraphics.fill(bx, by, bx + fillW, by + barH, OriginTheme.ACCENT);  // fill
		}
	}

	// ---- Loading ----

	// Volatile fast-path so the common (already-loaded) case is a plain field
	// read with no monitor -- ensureLoaded() runs several times per frame.
	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		ensureLoaded0();
	}

	private static synchronized void ensureLoaded0() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Minecraft mc = Minecraft.getInstance();
			JsonObject meta;
			try (InputStream in = open("/assets/originclient/textures/ui/rings.json")) {
				meta = GSON.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			}
			var arr = meta.getAsJsonArray("rings");
			for (int i = 0; i < arr.size(); i++) {
				JsonObject r = arr.get(i).getAsJsonObject();
				int index = r.get("index").getAsInt();
				ResourceLocation id = registerTexture(mc, "origin_ring_" + index,
						"/assets/originclient/textures/ui/ring-" + index + ".png");
				rings.add(new Ring(id,
						r.get("widthFrac").getAsDouble(),
						r.get("opacity").getAsFloat(),
						r.get("angle0").getAsDouble(),
						r.get("periodSeconds").getAsDouble(),
						r.get("reverse").getAsBoolean()));
			}
			grainId = registerTexture(mc, "origin_grain", "/assets/originclient/textures/ui/grain.png");
		} catch (Exception e) {
			ringsFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin screen ring/grain textures failed to load; using plain background", e);
		}

		// Vignette loads independently of the rings: a failure just skips the
		// edge darkening, it doesn't disable the backdrop.
		try {
			Minecraft mc = Minecraft.getInstance();
			vignetteId = registerTexture(mc, "origin_vignette", "/assets/originclient/textures/ui/vignette.png");
		} catch (Exception e) {
			vignetteId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin vignette failed to load; skipping edge vignette", e);
		}

		// Wordmark loads separately: a failure here falls back to vanilla-font
		// text without disabling the ring/grain background.
		try {
			Minecraft mc = Minecraft.getInstance();
			JsonObject wm;
			try (InputStream in = open("/assets/originclient/textures/ui/wordmark.json")) {
				wm = GSON.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			}
			wmTexW = wm.get("width").getAsInt();
			wmTexH = wm.get("height").getAsInt();
			wmInkX = wm.get("inkX").getAsInt();
			wmInkY = wm.get("inkY").getAsInt();
			wmInkW = wm.get("inkWidth").getAsInt();
			wmInkH = wm.get("inkHeight").getAsInt();
			// Per-letter reveal bands (optional): [[x0,x1],...] texture columns.
			if (wm.has("letters")) {
				var la = wm.getAsJsonArray("letters");
				int[] x0 = new int[la.size()];
				int[] x1 = new int[la.size()];
				for (int i = 0; i < la.size(); i++) {
					var band = la.get(i).getAsJsonArray();
					x0[i] = band.get(0).getAsInt();
					x1[i] = band.get(1).getAsInt();
				}
				wmLetterX0 = x0;
				wmLetterX1 = x1;
			} else {
				wmLetterX0 = null;
				wmLetterX1 = null;
			}
			wordmarkId = registerTexture(mc, "origin_wordmark", "/assets/originclient/textures/ui/wordmark.png");
		} catch (Exception e) {
			wordmarkId = null;
			wmLetterX0 = null;
			wmLetterX1 = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin wordmark failed to load; using vanilla font", e);
		}

		// Cursor-follow glow texture. Optional; skipped entirely on failure.
		try {
			Minecraft mc = Minecraft.getInstance();
			radialGlowId = registerTexture(mc, "origin_radial_glow", "/assets/originclient/textures/ui/radial_glow.png");
		} catch (Exception e) {
			radialGlowId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin radial glow failed to load; skipping cursor glow", e);
		}
	}

	private static ResourceLocation registerTexture(Minecraft mc, String name, String path) throws Exception {
		NativeImage image;
		try (InputStream in = open(path)) {
			image = NativeImage.read(in);
		}
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("originclient", name);
		DynamicTexture texture = new DynamicTexture(image);
		texture.setFilter(true, false); // GL_LINEAR, no mipmap
		mc.getTextureManager().register(id, texture);
		return id;
	}

	private static InputStream open(String classpathResource) throws Exception {
		InputStream in = OriginScreenRenderer.class.getResourceAsStream(classpathResource);
		if (in == null) {
			throw new java.io.FileNotFoundException("Missing Origin asset: " + classpathResource);
		}
		return in;
	}
}
