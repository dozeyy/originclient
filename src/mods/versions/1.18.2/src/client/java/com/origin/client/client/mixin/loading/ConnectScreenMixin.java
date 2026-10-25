package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Connecting to the server...": the one loading picture (the real logo, the plain
// capsule bar sweeping, the connection status as its caption), as on 1.21.1, over
// this version's own menu backdrop (ScreenBackgroundMixin: level == null while
// connecting).
//
// Targets (javap, identical on 1.16.5-1.19.4, Mojmap):
//  - ConnectScreen.render's one status-line draw,
//    invokestatic ConnectScreen.drawCenteredString(PoseStack, Font, Component, III)V
//    (owner ConnectScreen in the bytecode; the method is GuiComponent's static). It
//    sits after renderBackground and before Screen.render (which draws Cancel), so
//    redirecting just that call puts the picture between the backdrop and the
//    button, and vanilla's narration and state handling stay untouched. Vanilla
//    mode, or a failed Origin draw, draws vanilla's status line instead.
//  - ConnectScreen.init TAIL: Cancel is seated below the picture
//    (OriginScreenRenderer.connectCancelTop: 62 % of the height, never on the
//    caption), so the logo stays at the spot every loading screen uses.
// If either stops applying (required:false config): vanilla's status line and
// Cancel position come back; nothing breaks.
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$seatCancel(CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this) || !OriginScreenRenderer.isActive()) {
			return;   // vanilla mode: stock screen
		}
		try {
			Screen self = (Screen) (Object) this;
			for (GuiEventListener child : self.children()) {
				if (child instanceof AbstractWidget) {
					AbstractWidget w = (AbstractWidget) child;
					w.y = OriginScreenRenderer.connectCancelTop(self.height, w.getHeight());
				}
			}
		} catch (Throwable ignored) {
			// fail-soft: Cancel keeps vanilla's spot
		}
	}

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/ConnectScreen;drawCenteredString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void originclient$picture(PoseStack poseStack, Font font, Component status, int x, int y, int color) {
		if (OriginStyle.vanillaMenusOn(this) || !OriginScreenRenderer.renderConnecting(poseStack, status)) {
			GuiComponent.drawCenteredString(poseStack, font, status, x, y, color);
		}
	}
}
