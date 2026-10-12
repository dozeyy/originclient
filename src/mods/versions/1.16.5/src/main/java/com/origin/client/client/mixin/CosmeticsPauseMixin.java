package com.origin.client.client.mixin;

import com.origin.client.client.gui.CosmeticsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Cosmetics" button under the pause menu's own buttons, so a player
 * can change what they wear without leaving the world. Kept separate from
 * PauseScreenMixin (Smart Disconnect) like on 1.21.1. Fail-soft: if the layout
 * can't be read, the menu is left exactly as vanilla draws it.
 * 1.16.5: widgets have public x/y fields and a plain Button constructor
 * (Button.builder / getX are 1.19.3+), and widgets are added with
 * Screen.addButton (addRenderableWidget is 1.17+).
 */
@Mixin(PauseScreen.class)
public abstract class CosmeticsPauseMixin extends Screen {
	private static final int WIDTH = 204, HEIGHT = 20, GAP = 4;

	protected CosmeticsPauseMixin(Component title) {
		super(title);
	}

	@Inject(method = "createPauseMenu", at = @At("TAIL"))
	private void originclient$cosmeticsButton(CallbackInfo ci) {
		try {
			int bottom = Integer.MIN_VALUE;
			int left = 0;
			for (GuiEventListener child : this.children()) {
				if (child instanceof Button) {
					Button b = (Button) child;
					bottom = Math.max(bottom, b.y + b.getHeight());
					left = b.x;
				}
			}
			if (bottom == Integer.MIN_VALUE) {
				return;
			}
			final Screen self = this;
			this.addButton(new Button(left, bottom + GAP, WIDTH, HEIGHT, new TextComponent("Cosmetics"),
					b -> this.minecraft.setScreen(new CosmeticsScreen(self))));
		} catch (Throwable ignored) {
			// fail-soft: the pause menu stays vanilla
		}
	}
}
