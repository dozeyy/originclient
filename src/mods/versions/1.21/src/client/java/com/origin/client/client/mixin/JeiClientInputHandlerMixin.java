package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import mezz.jei.gui.input.ClientInputHandler;
import mezz.jei.gui.input.UserInput;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The INPUT half of the JEI toggle ({@link JeiGuiEventHandlerMixin} is the render
 * half). ClientInputHandler is where JEI's Fabric wiring routes every key, click,
 * scroll and typed character, so refusing here is refusing all of it.
 *
 * WHY THIS IS NOT REDUNDANT WITH HIDING THE OVERLAY. Hiding the overlay is not
 * enough to make JEI absent: FocusInputHandler answers the recipe/uses keys (R and
 * U by default) for whatever item is under the cursor in the ordinary vanilla
 * inventory, and GuiAreaInputHandler answers clicks on things like the crafting
 * arrow. Both open a full-screen JEI recipe GUI with no overlay involved at all.
 * That is exactly the "JEI cannot be unloaded, so off must also suppress its
 * recipe screens" constraint — and blocking input is what satisfies it. It also
 * closes the back door where ctrl+O would let JEI re-enable itself while Origin
 * says off.
 *
 * Blocking input at the root is why no separate mixin on RecipesGui is needed:
 * every route that opens it is an input event, so none of them fire.
 *
 * Returning false means "JEI did not handle this", which is the same answer JEI
 * gives for any key it doesn't care about — so vanilla proceeds normally. We are
 * using JEI's own contract, not fighting it.
 *
 * See {@link JeiGuiEventHandlerMixin} for the remap = false rationale and the
 * fail-soft caveat; both apply here too.
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
	private void originclient$blockCharPost(Screen screen, char codePoint, int modifiers, CallbackInfo ci) {
		if (!Mods.on("jei")) {
			ci.cancel();
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
	private void originclient$blockScroll(double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY,
			CallbackInfoReturnable<Boolean> cir) {
		if (!Mods.on("jei")) {
			cir.setReturnValue(false);
		}
	}
}
