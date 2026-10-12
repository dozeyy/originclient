package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.Hypixel;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

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
 *    EntityRenderer.renderNameTag).
 *
 * PER-VERSION (1.21.2+ render-state era, verified against the decompiled 1.21.4
 * and 1.21.8 PlayerRenderer / EntityRenderer / PlayerRenderState): the hook is
 * renderNameTag(PlayerRenderState, Component, PoseStack, MultiBufferSource, int)
 * -- no player, no partial tick. The state carries everything the label needs
 * (nameTagAttachment, scoreText for the below-name line, isDiscrete, camera
 * distance) except the UUID the level lookup is keyed by, which comes from the
 * state's entity id (state.id) through the client level. Full descriptor: keeps
 * the injectors off the synthetic renderNameTag(EntityRenderState, ...) bridge.
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
	protected abstract void renderNameTag(PlayerRenderState state, Component name, PoseStack pose,
										  MultiBufferSource buffers, int light);

	@Inject(method = "renderNameTag(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("HEAD"), cancellable = true)
	private void originclient$levelInline(PlayerRenderState state, Component name, PoseStack pose,
										  MultiBufferSource buffers, int light, CallbackInfo ci) {
		if (originclient$reentry || originclient$hidden() || Mods.bool("hypixel", "levelAbove")) {
			return;
		}
		Component level;
		try {
			level = originclient$level(state);
		} catch (Throwable t) {
			return;
		}
		if (level == null) {
			return;
		}
		originclient$reentry = true;
		try {
			renderNameTag(state, Component.empty().append(name).append(Component.literal(" ")).append(level),
					pose, buffers, light);
			ci.cancel();
		} catch (Throwable t) {
			// fall through: vanilla draws the plain tag
		} finally {
			originclient$reentry = false;
		}
	}

	@Inject(method = "renderNameTag(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("TAIL"))
	private void originclient$levelAbove(PlayerRenderState state, Component name, PoseStack pose,
										 MultiBufferSource buffers, int light, CallbackInfo ci) {
		if (originclient$reentry || originclient$hidden() || !Mods.bool("hypixel", "levelAbove")) {
			return;
		}
		try {
			Vec3 at = state.nameTagAttachment;
			if (at == null || state.distanceToCameraSq > 4096.0) {
				return;   // vanilla's own nametag cut-off
			}
			Component level = originclient$level(state);
			if (level == null) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			// Lines already drawn above the attachment: the name, plus the
			// below-name score when vanilla showed it (scoreText is only set
			// within its 10-block range).
			int lines = state.scoreText != null ? 2 : 1;
			boolean solid = !state.isDiscrete;
			Font font = mc.font;
			pose.pushPose();
			pose.translate(at.x, at.y + 0.5 + lines * originclient$LINE, at.z);
			pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
			pose.scale(0.025F, -0.025F, 0.025F);
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

	// The tagged player's level text, or null. The render state has only the
	// entity id; the level's entity lookup gives back the player (and its UUID).
	@Unique
	private static Component originclient$level(PlayerRenderState state) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		Entity e = mc.level.getEntity(state.id);
		if (e == null) {
			return null;
		}
		UUID id = e.getUUID();
		return Hypixel.levelHeadText(id);
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
