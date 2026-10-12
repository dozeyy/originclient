package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.hud.BossBarHud;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;

// Boss Bar mod: with the mod on, Origin lays the boss bars out itself (BossBarHud)
// -- vanilla's own drawBar, at the "bossbar" HUD element's position and scale --
// and vanilla's own loop is skipped. Called from vanilla's HUD, so z-order is
// unchanged. Mod off = vanilla untouched.
// 1.16.5 shape (decompiled 1.16.4): render(PoseStack) iterates
// the `events` map (a LinkedHashMap, insertion = display order) and draws each
// bar through private drawBar(PoseStack, int, int, BossEvent). If either shadow
// failed to bind, this whole mixin is skipped (config "required": false) and
// vanilla keeps drawing. Fail-soft per frame: a throw leaves the bars to vanilla.
@Mixin(BossHealthOverlay.class)
public abstract class BossBarMixin {
	@Shadow
	@Final
	private Map<UUID, LerpingBossEvent> events;

	@Shadow
	protected abstract void drawBar(PoseStack poseStack, int x, int y, BossEvent event);

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$bossBar(PoseStack poseStack, CallbackInfo ci) {
		if (!Mods.on("bossbar")) {
			return;
		}
		try {
			BossBarHud.render(new Gfx(poseStack), events.values(), this::drawBar);
			ci.cancel();
		} catch (Throwable t) {
			// leave the bars to vanilla this frame
		}
	}
}
