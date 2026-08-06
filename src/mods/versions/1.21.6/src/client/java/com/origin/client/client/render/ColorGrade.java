package com.origin.client.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.gui.OriginShaders;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.joml.Matrix3x2f;

/**
 * The Color Saturation mod's renderer: a full-screen grade over the rendered
 * WORLD (Saturation / Brightness / Contrast). Driven per-frame from GuiHudMixin at
 * the HEAD of Gui.render — after the world is fully drawn but BEFORE the HUD/menu —
 * so only the game world is graded, never the HUD, the menu, or the title screen.
 *
 * <p>PER-VERSION DELTA (1.21.11) — this is a different mechanism from 1.21.1's,
 * because none of 1.21.1's pieces exist here:
 *
 * <ul>
 * <li>1.21.1 bound a shader with {@code RenderSystem.setShader}, set three real
 *     uniforms, and issued an immediate {@code BufferUploader.drawWithShader}.
 *     There is no immediate-draw path on 1.21.11 at all, and no per-draw uniform
 *     hook — so the draw is a {@link GuiElementRenderState} submitted into
 *     {@code guiGraphics.guiRenderState} (exactly like OriginSdfFont's text), and
 *     the three amounts ride the per-vertex COLOR channel. See
 *     {@code rendertype_origin_grade.fsh} for the packing.</li>
 * <li>1.21.1 ping-ponged between the main target and a swap target with
 *     {@code bindWrite}. Here the copy is a straight GPU texture-to-texture blit
 *     through the command encoder, and the graded quad is then drawn back over
 *     the main target sampling that copy. A shader cannot sample the target it is
 *     writing to, which is the whole reason the copy exists.</li>
 * </ul>
 *
 * <p>What carried over unchanged from 1.21.1: the pre-HUD timing (so only world
 * pixels are graded), halving the deviation from neutral so the sliders are
 * gentle, the exact-neutral early-out that makes the mod free when unchanged,
 * and the fail-soft contract — if anything throws, {@code broken} latches and the
 * grade never runs again this session. The game keeps rendering normally; it
 * never crashes. See MEMORY's colorgrade-sky-fix for why a self-managed
 * PostChain is deliberately NOT used.
 */
public final class ColorGrade {
	private ColorGrade() {
	}

	private static TextureTarget swap;
	private static boolean broken = false;

	public static void process(GuiGraphics guiGraphics) {
		if (broken || OriginShaders.GRADE == null || !Mods.on("colorsaturation")) {
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
			RenderTarget main = mc.getMainRenderTarget();
			if (main == null || main.getColorTexture() == null) {
				return;
			}
			// Keep a colour-only swap target matched to the main framebuffer size.
			if (swap == null || swap.width != main.width || swap.height != main.height) {
				if (swap != null) {
					swap.destroyBuffers();
				}
				swap = new TextureTarget("origin_grade", main.width, main.height, false);
			}
			// Snapshot the finished world frame. The graded quad below samples THIS,
			// not the live target it is about to overwrite.
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
					main.getColorTexture(), swap.getColorTexture(), 0,
					0, 0, 0, 0, main.width, main.height);

			GpuTextureView view = swap.getColorTextureView();
			if (view == null) {
				return;
			}
			int w = guiGraphics.guiWidth(), h = guiGraphics.guiHeight();
			guiGraphics.guiRenderState.submitGuiElement(new GradeRenderState(
					new Matrix3x2f(guiGraphics.pose()), w, h, pack(sat, bri, con), view,
					new ScreenRectangle(0, 0, w, h)));
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Color Saturation grade failed; disabling for this session", t);
		}
	}

	/** Packs the three 0..2 amounts into one ARGB int, each as value/2 in a byte. */
	private static int pack(float sat, float bri, float con) {
		int r = clamp255(Math.round(sat / 2f * 255f));
		int g = clamp255(Math.round(bri / 2f * 255f));
		int b = clamp255(Math.round(con / 2f * 255f));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static int clamp255(int v) {
		return Math.max(0, Math.min(255, v));
	}

	/**
	 * One full-screen quad through {@link OriginShaders#GRADE}. Deliberately
	 * reports the whole screen as its bounds: the deferred renderer culls on
	 * bounds(), and a null there is read as "zero visible area" and silently
	 * dropped before buildVertices ever runs (the invisible-MSDF-text bug).
	 */
	private record GradeRenderState(Matrix3x2f pose, int w, int h, int color,
									GpuTextureView source, ScreenRectangle bounds) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer vc, float depth) {
			// This era passes the GUI depth to buildVertices; the quad is drawn at
			// the pose's own z, so the extra argument is simply unused here.
			// UVs are flipped vertically: the framebuffer's origin is bottom-left
			// while GUI space runs top-down, so sampling straight through would
			// draw the world upside down.
			vc.addVertexWith2DPose(pose, 0f, 0f, 0f).setColor(color).setUv(0f, 1f);
			vc.addVertexWith2DPose(pose, 0f, h, 0f).setColor(color).setUv(0f, 0f);
			vc.addVertexWith2DPose(pose, w, h, 0f).setColor(color).setUv(1f, 0f);
			vc.addVertexWith2DPose(pose, w, 0f, 0f).setColor(color).setUv(1f, 1f);
		}

		@Override
		public com.mojang.blaze3d.pipeline.RenderPipeline pipeline() {
			return OriginShaders.GRADE;
		}

		@Override
		public TextureSetup textureSetup() {
			// This era's singleTexture takes only the view; the explicit
			// clamp-to-edge sampler argument arrives in 1.21.11.
			return TextureSetup.singleTexture(source);
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
