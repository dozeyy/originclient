package com.origin.client.client.mixin;

import com.origin.client.client.gui.CosmeticsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
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
 * PauseScreenMixin (which is shared across versions).
 *
 * It must be indistinguishable from its neighbours, so nothing about it is
 * hand-tuned: it is a plain vanilla Button (skinned by OriginButtonRenderer
 * exactly like every other pause button), and its bounds are COPIED from the
 * menu's own full-width row (Back to Game / Save and Quit) with the menu's own
 * row gap. Fail-soft: if the layout can't be read, the menu stays vanilla.
 *
 * Pre-1.19.3 there is no grid: createPauseMenu places each row at a fixed
 * offset from height / 4 (top-anchored), so a taller stack keeps vanilla's
 * top and simply grows downward -- no lift, unlike 1.19.4+ (whose grid is
 * centred a quarter of the way down). Widget x/y are public fields here and
 * buttons are built with the constructor (no Button.builder yet).
 */
@Mixin(PauseScreen.class)
public abstract class CosmeticsPauseMixin extends Screen {
	protected CosmeticsPauseMixin(Component title) {
		super(title);
	}

	@Inject(method = "createPauseMenu", at = @At("TAIL"))
	private void originclient$cosmeticsButton(CallbackInfo ci) {
		try {
			List<Button> buttons = new ArrayList<>();
			originclient$collect(this.children(), buttons);
			if (buttons.isEmpty()) {
				return;
			}
			// The full-width row template: the widest button (Save and Quit / Back
			// to Game), and the lowest one for the bottom edge.
			Button wide = buttons.get(0);
			Button low = buttons.get(0);
			for (Button b : buttons) {
				if (b.getWidth() > wide.getWidth()) wide = b;
				if (b.y + b.getHeight() > low.y + low.getHeight()) low = b;
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
			final Screen self = this;
			this.addRenderableWidget(new Button(wide.x, low.y + low.getHeight() + gap, wide.getWidth(), h,
					Component.literal("Cosmetics"), b -> this.minecraft.setScreen(new CosmeticsScreen(self))));
		} catch (Throwable ignored) {
			// fail-soft: the pause menu stays vanilla
		}
	}

	/** Every Button among the screen's widgets (looking inside containers too, as 1.19.3+ does). */
	private static void originclient$collect(List<? extends GuiEventListener> children, List<Button> out) {
		for (GuiEventListener child : children) {
			if (child instanceof Button b) {
				out.add(b);
			} else if (child instanceof ContainerEventHandler c) {
				originclient$collect(c.children(), out);
			}
		}
	}
}
