package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginModMenuScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Replaces vanilla's title screen widgets with Origin's simplified menu —
// Singleplayer / Multiplayer / Settings / Mod Settings / Quit — matching the
// launcher's own minimal, one-primary-action visual language.
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	protected TitleScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$rebuildMenu(CallbackInfo ci) {
		this.clearWidgets();

		int buttonWidth = 200;
		int buttonHeight = 24;
		int spacing = 8;
		int centerX = this.width / 2 - buttonWidth / 2;
		int startY = this.height / 2 - 10;

		this.addRenderableWidget(Button.builder(Component.literal("Singleplayer"), b ->
						this.minecraft.setScreen(new SelectWorldScreen((TitleScreen) (Object) this)))
				.bounds(centerX, startY, buttonWidth, buttonHeight)
				.build());

		this.addRenderableWidget(Button.builder(Component.literal("Multiplayer"), b ->
						this.minecraft.setScreen(new JoinMultiplayerScreen((TitleScreen) (Object) this)))
				.bounds(centerX, startY + (buttonHeight + spacing), buttonWidth, buttonHeight)
				.build());

		this.addRenderableWidget(Button.builder(Component.literal("Settings"), b ->
						this.minecraft.setScreen(new OptionsScreen((TitleScreen) (Object) this, this.minecraft.options)))
				.bounds(centerX, startY + (buttonHeight + spacing) * 2, buttonWidth, buttonHeight)
				.build());

		this.addRenderableWidget(Button.builder(Component.literal("Mod Settings"), b ->
						this.minecraft.setScreen(new OriginModMenuScreen((TitleScreen) (Object) this)))
				.bounds(centerX, startY + (buttonHeight + spacing) * 3, buttonWidth, buttonHeight)
				.build());

		this.addRenderableWidget(Button.builder(Component.literal("Quit Game"), b -> this.minecraft.stop())
				.bounds(centerX, startY + (buttonHeight + spacing) * 4, buttonWidth, buttonHeight)
				.build());
	}
}
