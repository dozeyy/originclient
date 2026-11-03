package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.HitboxFacts;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: EntityRenderer.extractHitboxes(Entity, EntityRenderState, float) TAIL --
// the private step extractRenderState runs only while hitboxes are shown (the
// Hitbox mod turns them on), with the LIVE entity and its render state in hand.
//
// Why: on 1.21.6-1.21.10 the hitbox draw (HitboxMixin) only gets the render
// state, which has no live entity. This records what the Mod Menu's switches
// need (category, hurt, under the crosshair, is-it-you) against that state
// (HitboxFacts), so Passive / Monsters / Projectiles, Show Hittable Color and
// Show Damaged Color work the way they do on 1.21.1.
//
// If it stops applying: HitboxMixin falls back to the entity type alone --
// Passive / Monsters / Projectiles match nothing again and the tints and look
// line stay off. Nothing crashes.
@Mixin(EntityRenderer.class)
public class HitboxFactsMixin {

	@Inject(method = "extractHitboxes(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
			at = @At("TAIL"), require = 0, expect = 0)
	private void originclient$hitboxFacts(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
		try {
			if (Mods.on("hitboxes")) {
				HitboxFacts.capture(entity, state);
			}
		} catch (Throwable t) {
			// fail-soft: vanilla's hitbox extraction is already done
		}
	}
}
