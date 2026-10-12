package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.TntRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.Locale;

/**
 * TNT Countdown (Lunar parity): "Fuse: 2.35" floating above every lit TNT.
 *
 * The fuse is the TNT's own fuse -- synced from the server and counted down by
 * the client every tick, so it's exact -- minus the partial tick for smoothness,
 * plus the Adjust Fuse Time offset (servers with custom fuses). The number fades
 * from Countdown Color through yellow to red as the fuse burns (vanilla's 80-tick
 * fuse as the full scale), unless Static Countdown Color pins it.
 *
 * Drawn like a vanilla name tag: billboard at nametag scale, a faint see-through
 * pass with the optional backdrop, then the solid text. Full-bright so it stays
 * readable at night; 64-block range like name tags.
 *
 * PER-VERSION DELTA (1.21.11, TntRenderer / NameTagFeatureRenderer source-
 * verified): entity rendering is extract-then-submit. The fuse arrives on the
 * TntRenderState (fuseRemainingInTicks = getFuse() - partialTick + 1, so the
 * label subtracts that 1 back), the camera orientation on the CameraRenderState,
 * and the two text passes go in through SubmitNodeCollector.submitText -- the
 * same pose / scale / SEE_THROUGH-then-NORMAL pair vanilla's own name-tag
 * storage builds. Called from TntCountdownMixin at the end of TntRenderer.submit.
 */
public final class TntCountdown {
	private static final String MOD = "tntcountdown";
	private static final float FULL_FUSE_TICKS = 80f;   // PrimedTnt.DEFAULT_FUSE_TIME

	private TntCountdown() {
	}

	public static void submit(TntRenderState tnt, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (!Mods.on(MOD) || tnt.distanceToCameraSq > 4096.0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		float ticks = Math.max(0f, tnt.fuseRemainingInTicks - 1f + (float) Mods.num(MOD, "fuseOffset"));
		int numColor = OriginColorPicker.liveColor(MOD, "color");
		if (!Mods.bool(MOD, "staticColor")) {
			float r = Math.min(1f, ticks / FULL_FUSE_TICKS);
			numColor = r > 0.5f
					? OriginTheme.lerpColor(0xFFFFFF00, numColor, (r - 0.5f) * 2f)
					: OriginTheme.lerpColor(0xFFFF0000, 0xFFFFFF00, r * 2f);
		}
		MutableComponent text = Component.literal("Fuse: ")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(OriginColorPicker.liveColor(MOD, "prefixColor") & 0xFFFFFF)))
				.append(Component.literal(String.format(Locale.ROOT, "%.2f", ticks / 20f))
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(numColor & 0xFFFFFF))));
		FormattedCharSequence seq = text.getVisualOrderText();

		Font font = mc.font;
		pose.pushPose();
		try {
			pose.translate(0.0, tnt.boundingBoxHeight + 0.5, 0.0);
			pose.mulPose(camera.orientation);
			pose.scale(0.025F, -0.025F, 0.025F);
			float x = -font.width(text) / 2f;
			int bg = Mods.bool(MOD, "background")
					? (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24 : 0;
			int light = LightTexture.FULL_BRIGHT;
			collector.submitText(pose, x, 0, seq, false, Font.DisplayMode.SEE_THROUGH, light, 0x20FFFFFF, bg, 0);
			collector.submitText(pose, x, 0, seq, Mods.bool(MOD, "textShadow"), Font.DisplayMode.NORMAL, light, 0xFFFFFFFF, 0, 0);
		} finally {
			pose.popPose();
		}
	}
}
