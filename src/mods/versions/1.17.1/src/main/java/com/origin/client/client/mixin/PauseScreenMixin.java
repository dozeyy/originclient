package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
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
	// The button's label is the only way to find it: "Save and Quit to Title" in singleplayer,
	// "Disconnect" (menu.disconnect) on a server or Realm. Matching only the first meant the
	// prompt never showed on a server, the case it exists for (bug sweep 2, E-4).
	private static final Component RETURN_TO_MENU = new TranslatableComponent("menu.returnToMenu");
	private static final Component DISCONNECT = new TranslatableComponent("menu.disconnect");

	protected PauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "createPauseMenu", at = @At("TAIL"))
	private void originclient$smartDisconnect(CallbackInfo ci) {
		if (!Mods.bool(Mods.GENERAL_ID, "smartDisconnect")) {
			return;
		}
		Button target = null;
		for (GuiEventListener r : this.children()) {
			if (r instanceof Button b && (RETURN_TO_MENU.equals(b.getMessage()) || DISCONNECT.equals(b.getMessage()))) {
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
		// 1.19.2: Button.builder is 1.19.3+ — plain ctor + public x/y fields.
		Button guarded = new Button(original.x, original.y, original.getWidth(), original.getHeight(),
				original.getMessage(), b -> this.minecraft.setScreen(new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						original.onPress();
					} else {
						this.minecraft.setScreen(self);
					}
				},
				new TextComponent("Leave this world?"),
				new TextComponent("You'll disconnect from the current world or server."))));
		this.addRenderableWidget(guarded);
	}
}
