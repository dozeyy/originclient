package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.client.renderer.entity.state.TntRenderState;
import org.joml.Matrix4f;

/**
 * TNT Countdown (Lunar parity): "Fuse: 2.35" floating above every lit TNT.
 *
 * The fuse is PrimedTnt.getFuse() -- synced from the server and counted down by
 * the client every tick, so it's exact -- minus the partial tick for smoothness,
 * plus the Adjust Fuse Time offset (servers with custom fuses). The number fades
 * from Countdown Color through yellow to red as the fuse burns (vanilla's 80-tick
 * fuse as the full scale), unless Static Countdown Color pins it.
 *
 * Drawn like a vanilla name tag (EntityRenderer.renderNameTag, 1.21.1): billboard
 * at nametag scale, a faint see-through pass with the optional backdrop, then the
 * solid text. Full-bright so it stays readable at night; 64-block range like
 * name tags. Called from TntCountdownMixin at the end of TntRenderer.render.
 *
 * PER-VERSION (1.21.2+ render-state era): reads vanilla's TntRenderState instead
 * of the entity. Its fuseRemainingInTicks is getFuse() - partialTick + 1
 * (TntRenderer.extractRenderState), so the extra tick comes back off to show
 * exactly 1.21.1's number; the camera distance and box height are state fields.
 */
public final class TntCountdown {
	private static final String MOD = "tntcountdown";
	private static final float FULL_FUSE_TICKS = 80f;   // PrimedTnt.DEFAULT_FUSE_TIME

	private TntCountdown() {
	}

	public static void render(TntRenderState tnt, PoseStack pose, MultiBufferSource buffers) {
		if (!Mods.on(MOD)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		var dispatcher = mc.getEntityRenderDispatcher();
		if (tnt.distanceToCameraSq > 4096.0) {
			return;
		}
		float ticks = Math.max(0f, tnt.fuseRemainingInTicks - 1.0F + (float) Mods.num(MOD, "fuseOffset"));
		int numColor = OriginColorPicker.liveColor(MOD, "color");
		if (!Mods.bool(MOD, "staticColor")) {
			float r = Math.min(1f, ticks / FULL_FUSE_TICKS);
			numColor = r > 0.5f
					? OriginTheme.lerpColor(0xFFFFFF00, numColor, (r - 0.5f) * 2f)
					: OriginTheme.lerpColor(0xFFFF0000, 0xFFFFFF00, r * 2f);
		}
		MutableComponent text = Component.literal("Fuse: ")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(OriginColorPicker.liveColor(MOD, "prefixColor") & 0xFFFFFF)))
				.append(Component.literal(String.format("%.2f", ticks / 20f))
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(numColor & 0xFFFFFF))));

		Font font = mc.font;
		pose.pushPose();
		try {
			pose.translate(0.0, tnt.boundingBoxHeight + 0.5, 0.0);
			pose.mulPose(dispatcher.cameraOrientation());
			pose.scale(0.025F, -0.025F, 0.025F);
			Matrix4f m = pose.last().pose();
			float x = -font.width(text) / 2f;
			int bg = Mods.bool(MOD, "background")
					? (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24 : 0;
			int light = LightTexture.FULL_BRIGHT;
			font.drawInBatch(text, x, 0, 0x20FFFFFF, false, m, buffers, Font.DisplayMode.SEE_THROUGH, bg, light);
			font.drawInBatch(text, x, 0, 0xFFFFFFFF, Mods.bool(MOD, "textShadow"), m, buffers, Font.DisplayMode.NORMAL, 0, light);
		} finally {
			pose.popPose();
		}
	}
}
