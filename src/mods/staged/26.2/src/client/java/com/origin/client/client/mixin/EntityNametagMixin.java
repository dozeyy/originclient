package com.origin.client.client.mixin;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nametag Tweaks. 26.2 replaced renderNameTag with the submit-node
// submitNameDisplay(...)/extractNameTags(...), but the single gate that decides
// whether a tag is drawn at all — shouldShowName(entity, distance) — survives, so
// every toggle routes through it: Toggle All / Toggle Players hide keybinds, and
// the Third Person Nametag option that force-shows your OWN tag in F5 (normally
// hidden because you're the camera entity). Vanilla already hides tags in F1, so
// that case needs no handling here.
@Mixin(EntityRenderer.class)
public class EntityNametagMixin {

	@Inject(method = "shouldShowName", at = @At("RETURN"), cancellable = true)
	private void originclient$gateNametag(Entity entity, double distanceSq, CallbackInfoReturnable<Boolean> cir) {
		if (!Mods.on("nametags")) {
			return;
		}
		boolean player = entity instanceof Player;
		if (OriginClientMod.nametagsHidden || (OriginClientMod.playerNametagsHidden && player)) {
			cir.setReturnValue(false);
			return;
		}
		// Third Person Nametag: reveal the local player's own tag in any
		// third-person view. Only ever flips a hidden tag to shown.
		if (cir.getReturnValueZ()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (Mods.bool("nametags", "thirdPerson") && entity == mc.player
				&& !mc.options.getCameraType().isFirstPerson()) {
			cir.setReturnValue(true);
		}
	}
}
