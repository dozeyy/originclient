package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SETTINGS > General > Smart Disconnect. Wraps the pause menu's "Save and Quit
// to Title" / "Disconnect" button in a confirmation prompt so a stray click
// can't drop you out of a world or off a server. The vanilla button's own
// action is preserved verbatim (we just gate it behind a yes/no screen).
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
	private static final Component RETURN_TO_MENU = Component.translatable("menu.returnToMenu");

	protected PauseScreenMixin(Component title) {
		super(title);
	}

	// 26.2: createPauseMenu was folded into init(); the pause widgets are built
	// there now, so wrap the disconnect button at init TAIL.
	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$smartDisconnect(CallbackInfo ci) {
		if (!Mods.bool(Mods.GENERAL_ID, "smartDisconnect")) {
			return;
		}
		Button target = null;
		for (GuiEventListener r : this.children()) {
			if (r instanceof Button b && RETURN_TO_MENU.equals(b.getMessage())) {
				target = b;
				break;
			}
		}
		if (target == null) {
			return;
		}
		final Button original = target;
		final Screen self = this;
		this.removeWidget(original);
		Button guarded = Button.builder(original.getMessage(), b -> this.minecraft.setScreen(new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						original.onPress();
					} else {
						this.minecraft.setScreen(self);
					}
				},
				Component.literal("Leave this world?"),
				Component.literal("You'll disconnect from the current world or server."))))
				.bounds(original.getX(), original.getY(), original.getWidth(), original.getHeight())
				.build();
		this.addRenderableWidget(guarded);
	}
}
