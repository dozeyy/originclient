package com.origin.client.client.mixin.loading;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The "Saving world" / exit-to-title transient screen (menu.savingLevel), plus
// any other GenericMessageScreen message. 26.2 removed ReceivingLevelScreen, so
// this is the class that shows while a world saves and unloads on quit — it was
// the last vanilla-styled screen left in the flow.
//
// GenericMessageScreen declares its own extractBackground (the backdrop paint)
// but NOT extractRenderState, so we take over the backdrop there and paint the
// Origin loading scene (background + rings + the message as the title). The
// vanilla FocusableTextWidget would otherwise draw the same message a second
// time over our scene, so init() strips it — the Origin scene owns the title.
// renderLoadingScene is fail-soft: if it ever throws it returns false and vanilla
// draws instead, so this never crashes the shutdown path.
@Mixin(GenericMessageScreen.class)
public abstract class GenericMessageScreenMixin extends Screen {
	protected GenericMessageScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$stripVanillaText(CallbackInfo ci) {
		this.clearWidgets();
	}

	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$originScene(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginScreenRenderer.renderLoadingScene(guiGraphics, this.getTitle())) {
			ci.cancel();
		}
	}
}
