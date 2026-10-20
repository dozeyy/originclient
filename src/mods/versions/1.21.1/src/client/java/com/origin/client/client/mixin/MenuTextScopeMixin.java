package com.origin.client.client.mixin;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.MenuText;
import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.gui.OriginWidgetOwnership;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: Screen.renderWithTooltip(GuiGraphics,int,int,float) -- public final,
// the one entry every screen is drawn through (javap, mapped 1.21.1).
// Why: Will's clean look restyles the TEXT of Minecraft's own menus (titles,
// headings, labels) in Origin's Inter face. This marks the frame as "inside a
// vanilla menu" so the text mixins (GuiGraphicsCenteredTextMixin,
// StringWidgetMixin) know when to act, and lets OriginButtonRenderer pick the
// screen's single primary button for the frame. Never in-game HUD text,
// inventories, chat, signs, books or the credits, never Origin's own screens
// (Right Shift menu, HUD editor), never another mod's screen.
// If this stops applying: menu text stays in the vanilla font and no button is
// drawn as the primary -- everything still works.
@Mixin(Screen.class)
public class MenuTextScopeMixin {

	@Inject(method = "renderWithTooltip", at = @At("HEAD"))
	private void originclient$scopeOn(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		boolean on = false;
		try {
			on = originclient$styled(self);
			if (on) {
				OriginButtonRenderer.beginFrame(self);
			}
		} catch (Throwable ignored) {
			on = false;
		}
		MenuText.setScope(on);
	}

	@Inject(method = "renderWithTooltip", at = @At("RETURN"))
	private void originclient$scopeOff(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		MenuText.setScope(false);
	}

	private static boolean originclient$styled(Screen s) {
		if (OriginStyle.vanillaButtonsOn(s) || OriginWidgetOwnership.isOriginOwnClass(s)) {
			return false;
		}
		if (!s.getClass().getName().startsWith("net.minecraft.")) {
			return false;    // another mod's screen keeps its own look
		}
		return !(s instanceof AbstractContainerScreen<?> || s instanceof ChatScreen
				|| s instanceof AbstractSignEditScreen || s instanceof BookEditScreen
				|| s instanceof BookViewScreen || s instanceof WinScreen);
	}
}
