package com.origin.client.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.origin.client.client.gui.OriginShaders;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * The Color Saturation mod's renderer: a full-screen grade over the rendered
 * WORLD (Saturation / Brightness / Contrast). Driven per-frame from GuiHudMixin at
 * the HEAD of Gui.render — after the world is fully drawn but BEFORE the HUD/menu —
 * so only the game world is graded, never the HUD, the menu, or the title screen.
 *
 * <p>WHY THIS IS HAND-ROLLED (not a vanilla {@code PostChain}): a self-managed
 * PostChain run here reliably corrupted the NEXT vanilla frame's sky (a black
 * horizon band + a stale-framebuffer rectangle) whenever shaders were OFF — with
 * Iris shaders ON, which owns the pipeline, it was immune. The PostChain's opaque
 * target/state handling was the culprit. This version does the two passes itself
 * with STRICT GL-state discipline (the same fix that isolated the SDF menu draws):
 * grade the scene texture into our own swap target, copy it back to main, then
 * restore every bit of state the world render assumes at frame start. Nothing
 * leaks, so the sky renders correctly whether the grade is on or off.
 *
 * <p>Fail-soft: if the grade shader never loaded, or any GL step throws, it
 * latches {@code broken} and never runs again this session — the game keeps
 * rendering normally, never crashes. No-op when the mod is off or all three values
 * are ~1.0 (neutral).
 */
public final class ColorGrade {
	private ColorGrade() {
	}

	private static TextureTarget swap;
	private static boolean broken = false;

	public static void process(float partialTick) {
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
		RenderTarget main = mc.getMainRenderTarget();
		try {
			// Keep a color-only swap target matched to the main framebuffer size.
			if (swap == null || swap.width != main.width || swap.height != main.height) {
				if (swap != null) {
					swap.destroyBuffers();
				}
				swap = new TextureTarget(main.width, main.height, false, false);
				swap.setFilterMode(GL11.GL_NEAREST);
			}

			// Isolated fullscreen state: no blend (opaque overwrite), no depth test or
			// depth writes (so main's depth is left untouched), no face culling.
			RenderSystem.disableBlend();
			RenderSystem.disableDepthTest();
			RenderSystem.depthMask(false);
			RenderSystem.disableCull();
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

			// Pass 1: sample the rendered scene (main color) → graded → swap.
			swap.bindWrite(true);
			blit(main.getColorTextureId(), sat, bri, con);
			// Pass 2: copy the graded image back to main (neutral = passthrough).
			main.bindWrite(true);
			blit(swap.getColorTextureId(), 1f, 1f, 1f);

			// Restore every default the world/sky pass relies on at frame start.
			RenderSystem.enableDepthTest();
			RenderSystem.depthFunc(GL11.GL_LEQUAL);
			RenderSystem.depthMask(true);
			RenderSystem.enableCull();
			RenderSystem.disableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.disableScissor();
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Color Saturation grade failed; disabling for this session", t);
		}
	}

	/** Draw a fullscreen NDC quad sampling {@code srcTex} through the grade shader. */
	private static void blit(int srcTex, float sat, float bri, float con) {
		var sh = OriginShaders.GRADE;
		RenderSystem.setShader(() -> sh);
		RenderSystem.setShaderTexture(0, srcTex);
		sh.safeGetUniform("Saturation").set(sat);
		sh.safeGetUniform("Brightness").set(bri);
		sh.safeGetUniform("Contrast").set(con);
		BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
		bb.addVertex(-1f, -1f, 0f).setUv(0f, 0f);
		bb.addVertex(-1f, 1f, 0f).setUv(0f, 1f);
		bb.addVertex(1f, 1f, 0f).setUv(1f, 1f);
		bb.addVertex(1f, -1f, 0f).setUv(1f, 0f);
		MeshData mesh = bb.build();
		if (mesh != null) {
			BufferUploader.drawWithShader(mesh);
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
