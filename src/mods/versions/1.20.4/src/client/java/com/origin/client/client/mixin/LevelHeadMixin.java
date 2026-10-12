package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.Hypixel;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hypixel "Level Head": each player's Hypixel level (or Bed Wars star) on their
 * nametag. PlayerRenderer.renderNameTag is the one place every player's tag is
 * drawn (and only when vanilla decided the tag shows at all -- sneaking,
 * invisibility and team visibility rules all still apply).
 *
 *  - Level Above Name OFF (Lunar's default): the level is appended to the name
 *    on the same line. Done by re-running this very method with the longer name
 *    (guarded against re-entry), so vanilla's centring, the below-name score and
 *    every Nametags-mod style still apply to it.
 *  - Level Above Name ON: the tag draws as normal, then the level is drawn one
 *    line higher -- above the below-name score line too, when the server shows
 *    one -- with vanilla's own nametag transform (replicated from
 *    EntityRenderer.renderNameTag, 1.20.1-1.20.4: translate to getNameTagOffsetY(),
 *    face the camera, scale(-0.025, -0.025, 0.025) -- this era's cameraOrientation
 *    needs the mirrored X that 1.20.5 folded into the quaternion).
 *
 * Pre-1.20.5 shape (source-verified against decompiled 1.20.1 / 1.20.2 / 1.20.4):
 * PlayerRenderer.renderNameTag(AbstractClientPlayer, Component, PoseStack,
 * MultiBufferSource, int) -- no partial-tick argument, no entity attachments.
 *
 * Everything is fail-soft: no key, no stats yet, or any error = the vanilla tag.
 */
@Mixin(PlayerRenderer.class)
public abstract class LevelHeadMixin {
	// One nametag line in entity space: vanilla's own 9 * 1.15 * 0.025.
	@Unique
	private static final float originclient$LINE = 9.0F * 1.15F * 0.025F;
	@Unique
	private static boolean originclient$reentry = false;

	@Shadow
	protected abstract void renderNameTag(AbstractClientPlayer player, Component name, PoseStack pose,
										  MultiBufferSource buffers, int light);

	@Inject(method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("HEAD"), cancellable = true)
	private void originclient$levelInline(AbstractClientPlayer player, Component name, PoseStack pose,
										  MultiBufferSource buffers, int light, CallbackInfo ci) {
		if (originclient$reentry || originclient$hidden(player) || Mods.bool("hypixel", "levelAbove")) {
			return;
		}
		Component level;
		try {
			level = Hypixel.levelHeadText(player.getUUID());
		} catch (Throwable t) {
			return;
		}
		if (level == null) {
			return;
		}
		originclient$reentry = true;
		try {
			renderNameTag(player, Component.empty().append(name).append(Component.literal(" ")).append(level),
					pose, buffers, light);
			ci.cancel();
		} catch (Throwable t) {
			// fall through: vanilla draws the plain tag
		} finally {
			originclient$reentry = false;
		}
	}

	@Inject(method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("TAIL"))
	private void originclient$levelAbove(AbstractClientPlayer player, Component name, PoseStack pose,
										 MultiBufferSource buffers, int light, CallbackInfo ci) {
		if (originclient$reentry || originclient$hidden(player) || !Mods.bool("hypixel", "levelAbove")) {
			return;
		}
		try {
			Component level = Hypixel.levelHeadText(player.getUUID());
			if (level == null) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			var dispatcher = mc.getEntityRenderDispatcher();
			double distSq = dispatcher.distanceToSqr(player);
			if (distSq > 4096.0) {
				return;   // vanilla's own nametag cut-off
			}
			// Lines already drawn above the nametag anchor: the name, plus the
			// below-name score when vanilla showed it (within 10 blocks).
			int lines = 1;
			if (distSq < 100.0 && player.getScoreboard().getDisplayObjective(DisplaySlot.BELOW_NAME) != null) {
				lines = 2;
			}
			boolean solid = !player.isDiscrete();
			Font font = mc.font;
			pose.pushPose();
			pose.translate(0.0F, player.getNameTagOffsetY() + lines * originclient$LINE, 0.0F);
			pose.mulPose(dispatcher.cameraOrientation());
			pose.scale(-0.025F, -0.025F, 0.025F);
			Matrix4f m = pose.last().pose();
			int bg = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
			float x = -font.width(level) / 2f;
			font.drawInBatch(level, x, 0, 0x20FFFFFF, false, m, buffers,
					solid ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, bg, light);
			if (solid) {
				font.drawInBatch(level, x, 0, 0xFFFFFFFF, false, m, buffers, Font.DisplayMode.NORMAL, 0, light);
			}
			pose.popPose();
		} catch (Throwable t) {
			// never take a frame down over a level label
		}
	}

	// The Nametags mod's hide toggles (Toggle All / Toggle Players / Hide in F1)
	// hide the level with the tag.
	@Unique
	private static boolean originclient$hidden(AbstractClientPlayer player) {
		if (!Mods.on("hypixel") || !Mods.bool("hypixel", "levelHead")) {
			return true;
		}
		if (Mods.on("nametags")) {
			boolean f1 = Minecraft.getInstance().options.hideGui && Mods.bool("nametags", "hideInF1");
			return OriginClientMod.nametagsHidden || OriginClientMod.playerNametagsHidden || f1;
		}
		return false;
	}
}
