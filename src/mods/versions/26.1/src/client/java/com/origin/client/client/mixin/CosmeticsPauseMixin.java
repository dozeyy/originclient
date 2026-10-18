package com.origin.client.client.mixin;

import com.origin.client.client.gui.CosmeticsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds a "Cosmetics" row under the pause menu's own buttons, so a player can
 * change what they wear without leaving the world. Kept separate from
 * PauseScreenMixin (which is shared across versions) because cosmetics
 * don't ship on every version. Same on 1.21.11 as on 1.21.1: createPauseMenu
 * (what this module's PauseScreenMixin also hooks) builds the same GridLayout
 * of plain Buttons, placed by FrameLayout.alignInRectangle(..., 0.5F, 0.25F).
 *
 * It must be indistinguishable from its neighbours, so nothing about it is
 * hand-tuned: it is a plain vanilla Button (skinned by OriginButtonRenderer
 * exactly like every other pause button), and its bounds are COPIED from the
 * menu's own full-width row (Back to Game / Save and Quit) with the menu's own
 * row gap. Vanilla's grid sits a quarter of the way down the free space
 * (FrameLayout.alignInRectangle(..., 0.5F, 0.25F)), so a grid one row taller
 * would sit a quarter-row higher: the stack is lifted by that much, exactly as
 * if the row had been in vanilla's grid, and never above the title's 50px
 * clearance. Fail-soft: if the layout can't be read, the menu stays vanilla.
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
				if (b.getY() + b.getHeight() > low.getY() + low.getHeight()) low = b;
				top = Math.min(top, b.getY());
			}
			// The menu's own row gap: distance from the lowest row to the row above it.
			int gap = 4;
			int above = Integer.MIN_VALUE;
			for (Button b : buttons) {
				int bottom = b.getY() + b.getHeight();
				if (bottom <= low.getY()) above = Math.max(above, bottom);
			}
			if (above != Integer.MIN_VALUE) gap = low.getY() - above;
			int h = wide.getHeight();
			int lift = Math.max(0, Math.min((h + gap) / 4, top - TITLE_CLEARANCE));
			for (Button b : buttons) {
				b.setY(b.getY() - lift);
			}
			final Screen self = this;
			this.addRenderableWidget(Button.builder(Component.literal("Cosmetics"),
					b -> this.minecraft.setScreen(new CosmeticsScreen(self)))
					.bounds(wide.getX(), low.getY() + low.getHeight() + gap, wide.getWidth(), h).build());
		} catch (Throwable ignored) {
			// fail-soft: the pause menu stays vanilla
		}
	}
}
