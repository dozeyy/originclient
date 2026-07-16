package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import mezz.jei.common.input.ClientInputHandler;
import mezz.jei.common.input.UserInput;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The INPUT half of the JEI toggle for JEI 10 (MC 1.18.2). Like the 1.19.x build
 * (char/int char-typed, 3-arg single-axis scroll), but JEI 10 keeps these in the
 * {@code mezz.jei.common.input} package (moved to {@code mezz.jei.gui.input} at
 * JEI 11) and {@code onKeyboardCharTypedPost} RETURNS boolean here (it became void
 * later) — so its handler is a CallbackInfoReturnable, not a CallbackInfo.
 * javap-verified against jei-1.18.2-fabric-10.2.1.1002.
 * See {@link JeiGuiEventHandlerMixin} for the render half and the fail-soft note.
 */
@Mixin(value = ClientInputHandler.class, remap = false)
public abstract class JeiClientInputHandlerMixin {

	@Inject(method = "onKeyboardKeyPressedPre", at = @At("HEAD"), cancellable = true)
	private void originclient$blockKeyPre(Screen screen, UserInput input, CallbackInfoReturnable<Boolean> cir) {
		if (!Mods.on("jei")) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "onKeyboardKeyPressedPost", at = @At("HEAD"), cancellable = true)
	private void originclient$blockKeyPost(Screen screen, UserInput input, CallbackInfoReturnable<Boolean> cir) {
		if (!Mods.on("jei")) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "onKeyboardCharTypedPre", at = @At("HEAD"), cancellable = true)
	private void originclient$blockCharPre(Screen screen, char codePoint, int modifiers,
			CallbackInfoReturnable<Boolean> cir) {
		if (!Mods.on("jei")) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "onKeyboardCharTypedPost", at = @At("HEAD"), cancellable = true)
	private void originclient$blockCharPost(Screen screen, char codePoint, int modifiers,
			CallbackInfoReturnable<Boolean> cir) {
		if (!Mods.on("jei")) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "onGuiMouseClicked", at = @At("HEAD"), cancellable = true)
	private void originclient$blockClick(Screen screen, UserInput input, CallbackInfoReturnable<Boolean> cir) {
		if (!Mods.on("jei")) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "onGuiMouseReleased", at = @At("HEAD"), cancellable = true)
	private void originclient$blockRelease(Screen screen, UserInput input, CallbackInfoReturnable<Boolean> cir) {
		if (!Mods.on("jei")) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "onGuiMouseScroll", at = @At("HEAD"), cancellable = true)
	private void originclient$blockScroll(double mouseX, double mouseY, double scrollDelta,
			CallbackInfoReturnable<Boolean> cir) {
		if (!Mods.on("jei")) {
			cir.setReturnValue(false);
		}
	}
}
