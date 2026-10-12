package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.item.PrimedTnt;

/**
 * TNT Countdown (Lunar parity): "Fuse: 2.35" floating above every lit TNT.
 *
 * The fuse is PrimedTnt.getFuse() -- synced from the server and counted down by
 * the client every tick, so it's exact -- minus the partial tick for smoothness,
 * plus the Adjust Fuse Time offset (servers with custom fuses). The number fades
 * from Countdown Color through yellow to red as the fuse burns (vanilla's 80-tick
 * fuse as the full scale), unless Static Countdown Color pins it.
 *
 * Drawn like a vanilla name tag (EntityRenderer.renderNameTag, decompiled 1.19.4
 * / 1.18.1): billboard at nametag scale -- pre-1.21 that is scale(-0.025, -0.025,
 * 0.025) after the camera orientation -- a faint see-through pass with the
 * optional backdrop, then the solid text. Full-bright so it stays readable at night; 64-block range like
 * name tags. Called from TntCountdownMixin at the end of TntRenderer.render.
 */
public final class TntCountdown {
	private static final String MOD = "tntcountdown";
	private static final float FULL_FUSE_TICKS = 80f;   // PrimedTnt.DEFAULT_FUSE_TIME

	private TntCountdown() {
	}

	public static void render(PrimedTnt tnt, float partialTick, PoseStack pose, MultiBufferSource buffers) {
		if (!Mods.on(MOD)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		var dispatcher = mc.getEntityRenderDispatcher();
		if (dispatcher.distanceToSqr(tnt) > 4096.0) {
			return;
		}
		float ticks = Math.max(0f, tnt.getFuse() - partialTick + (float) Mods.num(MOD, "fuseOffset"));
		int numColor = OriginColorPicker.liveColor(MOD, "color");
		if (!Mods.bool(MOD, "staticColor")) {
			float r = Math.min(1f, ticks / FULL_FUSE_TICKS);
			numColor = r > 0.5f
					? OriginTheme.lerpColor(0xFFFFFF00, numColor, (r - 0.5f) * 2f)
					: OriginTheme.lerpColor(0xFFFF0000, 0xFFFFFF00, r * 2f);
		}
		MutableComponent text = new TextComponent("Fuse: ")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(OriginColorPicker.liveColor(MOD, "prefixColor") & 0xFFFFFF)))
				.append(new TextComponent(String.format("%.2f", ticks / 20f))
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(numColor & 0xFFFFFF))));

		Font font = mc.font;
		pose.pushPose();
		try {
			pose.translate(0.0, tnt.getBbHeight() + 0.5, 0.0);
			Era.faceCamera(pose);
			pose.scale(-0.025F, -0.025F, 0.025F);
			float x = -font.width(text) / 2f;
			int bg = Mods.bool(MOD, "background")
					? (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24 : 0;
			int light = LightTexture.FULL_BRIGHT;
			Era.drawWorldText(font, text, x, 0, 0x20FFFFFF, false, pose, buffers, true, bg, light);
			Era.drawWorldText(font, text, x, 0, 0xFFFFFFFF, Mods.bool(MOD, "textShadow"), pose, buffers, false, 0, light);
		} finally {
			pose.popPose();
		}
	}
}
