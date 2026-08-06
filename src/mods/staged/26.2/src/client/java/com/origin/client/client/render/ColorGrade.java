package com.origin.client.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.gui.OriginShaders;
import com.origin.client.client.mixin.GuiGraphicsExtractorAccessor;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/**
 * The Color Saturation mod's renderer: a full-screen grade over the rendered
 * WORLD (Saturation / Brightness / Contrast). Registered as a Fabric HUD element
 * with {@code addFirst} in OriginClientMod, so it runs BEFORE any vanilla HUD
 * element draws — after the world is fully in the main target, so only the game
 * world is graded, never the HUD, menu, or title screen.
 *
 * <p>PER-VERSION DELTA (26.2) — this mirrors the 1.21.11 mechanism (same render
 * era: no immediate-mode draw, no per-draw uniforms), with the 26.2 API renames:
 * <ul>
 * <li>The three amounts ride the per-vertex COLOR channel (see
 *     {@code rendertype_origin_grade.fsh}); the draw is a {@link GuiElementRenderState}
 *     submitted through {@code guiRenderState.addGuiElement} (was {@code submitGuiElement}).</li>
 * <li>The world snapshot is a GPU texture-to-texture copy through the command
 *     encoder; the graded quad samples that copy (a shader can't sample the target
 *     it writes to). The main target is now {@code gameRenderer.mainRenderTarget()}
 *     (was {@code Minecraft.getMainRenderTarget()}), and {@link TextureTarget} now
 *     needs an explicit {@link GpuFormat}.</li>
 * <li>{@code GuiGraphicsExtractor.guiRenderState} went private → reached via
 *     {@link GuiGraphicsExtractorAccessor}.</li>
 * </ul>
 *
 * <p>Carried over unchanged: the pre-HUD timing (world pixels only), halving the
 * deviation from neutral so the sliders are gentle, the exact-neutral early-out
 * (free when unchanged), and the fail-soft latch — if anything throws, {@code broken}
 * latches and the grade never runs again this session; the game keeps rendering
 * normally, never crashes. See MEMORY colorgrade-sky-fix for why a self-managed
 * PostChain is deliberately NOT used.
 */
public final class ColorGrade {
	private ColorGrade() {
	}

	private static TextureTarget swap;
	private static boolean broken = false;
	// Set by process() (GUI extraction) when a grade element is submitted this frame;
	// read+cleared by captureWorld() (GuiRenderer.render HEAD) which does the actual
	// GPU snapshot. The two are split because the texture copy MUST run at a real GPU
	// point (the start of the GUI flush, world in the main target, GUI not yet drawn),
	// NOT during extraction — issuing the copy in extraction captured a stale/empty
	// frame (the "ghost of the last menu + black elsewhere" bug, 2026-07-30).
	private static volatile boolean pendingCapture = false;

	public static void process(GuiGraphicsExtractor g) {
		if (broken || OriginShaders.grade() == null || !Mods.on("colorsaturation")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		// Only in a world (skip title screen etc.). Runs pre-HUD, so only world pixels
		// are graded; stays live even behind the open mod menu (world renders behind it).
		if (mc.level == null) {
			return;
		}
		// Sliders are multipliers around 1.0 (neutral). Halve the deviation from
		// neutral so the effect is gentler — at either extreme it's half as strong.
		float sat = (float) half(Mods.num("colorsaturation", "saturation"));
		float bri = (float) half(Mods.num("colorsaturation", "brightness"));
		float con = (float) half(Mods.num("colorsaturation", "contrast"));
		// Neutral (all ~1.0) → skip entirely (zero cost when unchanged).
		if (near1(sat) && near1(bri) && near1(con)) {
			return;
		}
		try {
			// Arm the snapshot; captureWorld() (GuiRenderer.render HEAD) sizes `swap`
			// to the REAL framebuffer texture and fills it with the live world frame
			// just before this element draws. The element fetches swap's view LIVE at
			// draw time (textureSetup below), so it never holds a stale/wrong-sized
			// reference — the quad covers the screen via UV 0..1 regardless of the
			// swap's pixel resolution.
			pendingCapture = true;
			int w = g.guiWidth(), h = g.guiHeight();
			((GuiGraphicsExtractorAccessor) g).originclient$guiRenderState().addGuiElement(new GradeRenderState(
					new Matrix3x2f(g.pose()), w, h, pack(sat, bri, con),
					new ScreenRectangle(0, 0, w, h)));
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Color Saturation grade failed; disabling for this session", t);
		}
	}

	/**
	 * GPU snapshot of the finished world frame into {@code swap}, called from
	 * GuiRendererMixin at {@code GuiRenderer.render()} HEAD — the moment the world is
	 * in the main target and the GUI has not drawn yet. The graded quad (submitted in
	 * {@link #process}) samples this copy; a shader can't sample the target it writes
	 * to, which is why the copy exists. Only runs when process() armed it this frame.
	 */
	public static void captureWorld() {
		if (broken || !pendingCapture) {
			return;
		}
		pendingCapture = false;
		try {
			RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			if (main == null || main.getColorTexture() == null) {
				return;
			}
			// Use the REAL texture dimensions, not RenderTarget.width/height — on a
			// high-DPI display those differ (the target reports a logical 854x480 while
			// the actual colour texture is e.g. 2560x1369). Sizing the swap or the copy
			// rectangle to the logical size copies only a corner (the ghost/black bug)
			// or throws "dest not large enough".
			var srcTex = main.getColorTexture();
			int tw = srcTex.getWidth(0), th = srcTex.getHeight(0);
			if (tw <= 0 || th <= 0) {
				return;
			}
			if (swap == null || swap.width != tw || swap.height != th) {
				if (swap != null) {
					swap.destroyBuffers();
				}
				swap = new TextureTarget("origin_grade", tw, th, false, GpuFormat.RGBA8_UNORM);
			}
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
					srcTex, swap.getColorTexture(), 0, 0, 0, 0, 0, tw, th);
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Color Saturation snapshot failed; disabling for this session", t);
		}
	}

	/** The current world snapshot's texture view (or null before the first capture). */
	private static GpuTextureView swapView() {
		return swap == null ? null : swap.getColorTextureView();
	}

	/** Packs the three 0..2 amounts into one ARGB int, each as value/2 in a byte. */
	private static int pack(float sat, float bri, float con) {
		int r = clamp255(Math.round(sat / 2f * 255f));
		int gg = clamp255(Math.round(bri / 2f * 255f));
		int b = clamp255(Math.round(con / 2f * 255f));
		return 0xFF000000 | (r << 16) | (gg << 8) | b;
	}

	private static int clamp255(int v) {
		return Math.max(0, Math.min(255, v));
	}

	/**
	 * One full-screen quad through {@link OriginShaders#GRADE}. Deliberately
	 * reports the whole screen as its bounds: the deferred renderer culls on
	 * bounds(), and a null there is read as "zero visible area" and silently
	 * dropped before buildVertices ever runs (the invisible-element trap).
	 */
	private record GradeRenderState(Matrix3x2fc pose, int w, int h, int color,
									ScreenRectangle bounds) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer vc) {
			// UVs are flipped vertically: the framebuffer's origin is bottom-left
			// while GUI space runs top-down, so sampling straight through would
			// draw the world upside down.
			vc.addVertexWith2DPose(pose, 0f, 0f).setColor(color).setUv(0f, 1f);
			vc.addVertexWith2DPose(pose, 0f, h).setColor(color).setUv(0f, 0f);
			vc.addVertexWith2DPose(pose, w, h).setColor(color).setUv(1f, 0f);
			vc.addVertexWith2DPose(pose, w, 0f).setColor(color).setUv(1f, 1f);
		}

		@Override
		public com.mojang.blaze3d.pipeline.RenderPipeline pipeline() {
			return OriginShaders.grade();
		}

		@Override
		public TextureSetup textureSetup() {
			// Fetch the swap view LIVE — this runs during the GUI flush, AFTER
			// captureWorld() (GuiRenderer.render HEAD) has sized + filled swap this
			// frame, so it's always the current world snapshot at the right resolution.
			GpuTextureView view = swapView();
			if (view == null) {
				return TextureSetup.noTexture();
			}
			return TextureSetup.singleTexture(view,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
		}

		@Override
		public ScreenRectangle scissorArea() {
			return null;
		}

		@Override
		public ScreenRectangle bounds() {
			return bounds;
		}
	}

	// Half the distance from neutral (1.0): value v → 1 + (v - 1) * 0.5.
	private static double half(double v) {
		return 1.0 + (v - 1.0) * 0.5;
	}

	private static boolean near1(double v) {
		return Math.abs(v - 1.0) < 0.01;
	}
}
