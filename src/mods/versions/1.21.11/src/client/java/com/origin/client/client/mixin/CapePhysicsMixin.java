package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.cosmetics.CapePhysics;
import com.origin.client.client.cosmetics.Cosmetics;
import com.origin.client.client.cosmetics.CosmeticsService;
import com.origin.client.client.cosmetics.MenuPlayerPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cape physics for Origin capes. CapePhysics.render submits the whole cape
 * itself (a spring-chain of cloth segments, see its class doc) and returns
 * true, in which case vanilla's stiff version is cancelled. For vanilla /
 * Mojang capes, the "Cape physics" setting off, elytra, an invisible wearer
 * or ANY failure it returns false (or throws, which is swallowed here) and
 * vanilla CapeLayer.submit runs untouched.
 *
 * PER-VERSION DELTA (1.21.11, extract/submit era): CapeLayer.submit(PoseStack,
 * SubmitNodeCollector, int, AvatarRenderState, float yRot, float xRot) -- the
 * same shape as the RenderLayer.submit override in cosmetics/CosmeticLayer.
 * The state carries no UUID and no partial tick: the wearer is looked up by
 * the state's entity id (the LevelHeadMixin pattern) and the partial tick
 * comes from the frame's DeltaTracker. The Cosmetics screen's no-world preview
 * (MenuPlayerPreview.STATE_ID) has no entity: it keeps vanilla's cape, moved
 * back / hidden for the items it wears, exactly like 1.21.1's preview. The
 * HEAD push is popped at RETURN: vanilla's submitModel copies the pose when
 * it is called, so the extra offset reaches the queued cape and nothing else.
 */
@Mixin(CapeLayer.class)
public abstract class CapePhysicsMixin {
	private static final String SUBMIT = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V";

	/** True between the HEAD push (vanilla cape drawn with an extra offset) and its matching RETURN pop. Render thread only. */
	private static boolean origin$pushed = false;

	@Inject(method = SUBMIT, at = @At("HEAD"), cancellable = true)
	private void originclient$capePhysics(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot, CallbackInfo ci) {
		origin$pushed = false;
		try {
			CosmeticsService.Look look = MenuPlayerPreview.lookFor(state);
			AbstractClientPlayer player = null;
			if (look == null) {
				Minecraft mc = Minecraft.getInstance();
				Entity e = mc.level != null ? mc.level.getEntity(state.id) : null;
				if (!(e instanceof AbstractClientPlayer p)) {
					return;   // vanilla draws the cape
				}
				player = p;
				look = CosmeticsService.lookOf(p.getUUID());
			}
			if (look.hidesCape()) {
				ci.cancel();   // wings / a backpack replace the cape, whichever cape it is
				return;
			}
			if (player != null) {
				float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
				if (CapePhysics.render(pose, collector, light, player, state, partial)) {
					ci.cancel();
					return;
				}
			}
			float off = look.capeOffset();
			// the player's height tweak, only for an Origin cape (model y is down)
			float up = look.cape() != null ? look.adjust(Cosmetics.Slot.CAPE) : 0f;
			if (off > 0f || up != 0f) {
				// Vanilla draws the cape: move it back for items worn on the back, and up / down
				// by the player's tweak (undone at RETURN).
				pose.pushPose();
				pose.translate(0f, -up / 16f, off / 16f);
				origin$pushed = true;
			}
		} catch (Throwable t) {
			// vanilla draws the cape instead
		}
	}

	@Inject(method = SUBMIT, at = @At("RETURN"))
	private void originclient$capeOffsetDone(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot, CallbackInfo ci) {
		if (origin$pushed) {
			origin$pushed = false;
			pose.popPose();
		}
	}
}
