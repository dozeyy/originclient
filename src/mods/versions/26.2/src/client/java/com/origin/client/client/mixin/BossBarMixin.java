package com.origin.client.client.mixin;

import com.origin.client.client.hud.BossBarHud;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;

// Boss Bar mod: with the mod on, Origin draws the boss bars itself (BossBarHud)
// -- same sprites and layout as vanilla, at the "bossbar" HUD element's position
// and scale -- and vanilla's own draw is skipped. Called from vanilla's HUD layer,
// so z-order is unchanged. Mod off = vanilla untouched.
// 1.21.1 shape (source-verified): render(GuiGraphicsExtractor) iterates the
// package-private `events` map (a LinkedHashMap, insertion = display order).
// Fail-soft: if Origin's draw throws, vanilla draws the bars instead.
@Mixin(BossHealthOverlay.class)
public class BossBarMixin {
	@Shadow
	@Final
	Map<UUID, LerpingBossEvent> events;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$bossBar(GuiGraphicsExtractor guiGraphics, CallbackInfo ci) {
		if (!Mods.on("bossbar")) {
			return;
		}
		try {
			BossBarHud.render(guiGraphics, events.values());
			ci.cancel();
		} catch (Throwable t) {
			// leave the bars to vanilla this frame
		}
	}
}
