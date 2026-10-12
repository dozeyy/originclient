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

import java.util.ArrayList;
import java.util.List;

/**
 * Adds a "Cosmetics" row under the pause menu's own buttons, so a player can
 * change what they wear without leaving the world. Kept separate from
 * PauseScreenMixin (Smart Disconnect) like on 1.21.1.
 *
 * It must be indistinguishable from its neighbours, so nothing about it is
 * hand-tuned: it is a plain vanilla Button (skinned by OriginButtonRenderer
 * exactly like every other pause button), and its bounds are COPIED from the
 * menu's own full-width row (Back to Game / Save and Quit) with the menu's own
 * row gap. The stack is lifted by a quarter row, as 1.21.1 does (there vanilla's
 * grid sits a quarter of the way down the free space, so one more row lifts it
 * by that much), never above the title's 50px clearance, so the two versions'
 * pause menus balance the same. Fail-soft: if the layout can't be read, the
 * menu stays vanilla.
 *
 * 1.17.1: the pause menu is not a GridLayout (fixed rows from height/4), widgets
 * have public x/y fields and a plain Button constructor (Button.builder / getX
 * are 1.19.3+), the same shapes PauseScreenMixin uses.
 */
@Mixin(PauseScreen.class)
public abstract class CosmeticsPauseMixin extends Screen {
	/** Vanilla's first-row top padding (room for the "Game Menu" title). */
	private static final int TITLE_CLEARANCE = 50;

	protected CosmeticsPauseMixin(Component title) {
		super(title);
	}

	@Inject(method = "createPauseMenu", at = @At("TAIL"))
	private void originclient$cosmeticsButton(CallbackInfo ci) {
		try {
			List<Button> buttons = new ArrayList<>();
			for (GuiEventListener child : this.children()) {
				if (child instanceof Button b) {
					buttons.add(b);
				}
			}
			if (buttons.isEmpty()) {
				return;
			}
			// The full-width row template: the widest button (Save and Quit / Back
			// to Game), and the lowest one for the bottom edge.
			Button wide = buttons.get(0);
			Button low = buttons.get(0);
			int top = Integer.MAX_VALUE;
			for (Button b : buttons) {
				if (b.getWidth() > wide.getWidth()) wide = b;
				if (b.y + b.getHeight() > low.y + low.getHeight()) low = b;
				top = Math.min(top, b.y);
			}
			// The menu's own row gap: distance from the lowest row to the row above it.
			int gap = 4;
			int above = Integer.MIN_VALUE;
			for (Button b : buttons) {
				int bottom = b.y + b.getHeight();
				if (bottom <= low.y) above = Math.max(above, bottom);
			}
			if (above != Integer.MIN_VALUE) gap = low.y - above;
			int h = wide.getHeight();
			int lift = Math.max(0, Math.min((h + gap) / 4, top - TITLE_CLEARANCE));
			for (Button b : buttons) {
				b.y -= lift;
			}
			final Screen self = this;
			this.addRenderableWidget(new Button(wide.x, low.y + low.getHeight() + gap, wide.getWidth(), h,
					new TextComponent("Cosmetics"), b -> this.minecraft.setScreen(new CosmeticsScreen(self))));
		} catch (Throwable ignored) {
			// fail-soft: the pause menu stays vanilla
		}
	}
}
