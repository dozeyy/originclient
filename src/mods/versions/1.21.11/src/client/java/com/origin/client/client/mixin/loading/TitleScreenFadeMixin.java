package com.origin.client.client.mixin.loading;

import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The startup title screen skips vanilla's 2 s fade-in when the Origin menu is
// on. Vanilla draws nothing but the background for the first second of that
// fade (no buttons, no logo), which left the Origin title empty right after the
// loading screen. Origin's own entrance (the mark settling, the buttons rising
// — TitleScreenMixin, timed from StartupTransition) replaces it, and the menu
// is clickable the moment the loading screen is gone.
//
// A fadeInStart already 2 s in the past makes vanilla's first frame its last
// fade frame: it clears its own fading state and draws everything at full alpha.
// Lives in the isolated loading config (title-screen timing belongs to the
// launch journey); a shadow that doesn't resolve only brings vanilla's fade back.
@Mixin(TitleScreen.class)
public abstract class TitleScreenFadeMixin {
	@Shadow
	private long fadeInStart;

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$skipVanillaFade(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (this.fadeInStart == 0L && OriginScreenRenderer.isActive()
				&& Mods.mode(Mods.GENERAL_ID, "mainMenuStyle").equals("Origin")) {
			this.fadeInStart = Util.getMillis() - 2001L;
		}
	}
}
