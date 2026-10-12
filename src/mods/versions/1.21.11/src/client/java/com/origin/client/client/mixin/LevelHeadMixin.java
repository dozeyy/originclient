package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.Hypixel;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hypixel "Level Head": each player's Hypixel level (or Bed Wars star) on their
 * nametag -- only when vanilla decided the tag shows at all (sneaking,
 * invisibility and team visibility rules all still apply).
 *
 *  - Level Above Name OFF (Lunar's default): the level is appended to the name
 *    on the same line, so vanilla's centring and every Nametags-mod style still
 *    apply to it.
 *  - Level Above Name ON: the tag draws as normal, then the level is submitted
 *    one line higher -- above the below-name score line too, when the server
 *    shows one -- through vanilla's own name-tag submit.
 *
 * 1.21.11 (source-verified): player rendering is extract-then-submit and the
 * player renderer is AvatarRenderer. 1.21.1 re-ran PlayerRenderer.renderNameTag
 * with the longer name; that method is gone, so the inline level goes into the
 * extracted AvatarRenderState.nameTag at the end of extractRenderState (the one
 * place the live player is in hand), and the "above" line is one more
 * SubmitNodeCollector.submitNameTag at the end of AvatarRenderer.submitNameTag,
 * stacked exactly the way vanilla stacks the name over the score (translate by
 * one line, same attachment / ear offset / see-through / light / distance).
 *
 * Everything is fail-soft: no key, no stats yet, or any error = the vanilla tag.
 */
@Mixin(AvatarRenderer.class)
public abstract class LevelHeadMixin {
	// One nametag line in entity space: vanilla's own 9 * 1.15 * 0.025.
	@Unique
	private static final float originclient$LINE = 9.0F * 1.15F * 0.025F;

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void originclient$levelInline(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		if (state.nameTag == null || !(entity instanceof AbstractClientPlayer)
				|| originclient$hidden() || Mods.bool("hypixel", "levelAbove")) {
			return;
		}
		try {
			Component level = Hypixel.levelHeadText(entity.getUUID());
			if (level != null) {
				state.nameTag = Component.empty().append(state.nameTag).append(Component.literal(" ")).append(level);
			}
		} catch (Throwable t) {
			// keep the plain tag
		}
	}

	@Inject(method = "submitNameTag(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
			at = @At("TAIL"), require = 0)
	private void originclient$levelAbove(AvatarRenderState state, PoseStack pose, SubmitNodeCollector collector,
										 CameraRenderState camera, CallbackInfo ci) {
		if (state.nameTag == null || state.nameTagAttachment == null
				|| originclient$hidden() || !Mods.bool("hypixel", "levelAbove")) {
			return;
		}
		try {
			if (state.distanceToCameraSq > 4096.0) {
				return;   // vanilla's own nametag cut-off
			}
			Minecraft mc = Minecraft.getInstance();
			Entity e = mc.level == null ? null : mc.level.getEntity(state.id);
			if (!(e instanceof AbstractClientPlayer player)) {
				return;
			}
			Component level = Hypixel.levelHeadText(player.getUUID());
			if (level == null) {
				return;
			}
			// Lines already drawn above the attachment: the name, plus the
			// below-name score when vanilla extracted one.
			int lines = state.scoreText != null ? 2 : 1;
			int earOffset = state.showExtraEars ? -10 : 0;
			pose.pushPose();
			pose.translate(0.0F, lines * originclient$LINE, 0.0F);
			collector.submitNameTag(pose, state.nameTagAttachment, earOffset, level, !state.isDiscrete,
					state.lightCoords, state.distanceToCameraSq, camera);
			pose.popPose();
		} catch (Throwable t) {
			// never take a frame down over a level label
		}
	}

	// The Nametags mod's hide toggles (Toggle All / Toggle Players / Hide in F1)
	// hide the level with the tag.
	@Unique
	private static boolean originclient$hidden() {
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
