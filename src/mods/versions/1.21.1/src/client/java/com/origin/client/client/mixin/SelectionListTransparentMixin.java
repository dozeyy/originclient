package com.origin.client.client.mixin;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Transparent Menus (Settings > General), the part MenuBlurMixin cannot reach: in a
// world, every vanilla list screen (options, video settings, controls, world/server
// lists, resource packs...) tiles a dark "inworld_menu_list_background" strip behind its
// rows and header/footer separator lines on top (AbstractSelectionList.renderListBackground
// / renderListSeparators, both javap-confirmed on the mapped 1.21.1 jar). With the option
// on, both are skipped so the world shows through the whole screen. Out of a world
// nothing changes (MenuBlur.transparent() needs a level), and the option off = vanilla.
// If this stops applying, those screens keep a dark list strip over an otherwise clear menu.
@Mixin(AbstractSelectionList.class)
public class SelectionListTransparentMixin {

	@Inject(method = "renderListBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$clearListBackground(GuiGraphics guiGraphics, CallbackInfo ci) {
		if (MenuBlur.transparent() && !OriginStyle.vanillaMenusOn(Minecraft.getInstance().screen)) {
			ci.cancel();
		}
	}

	@Inject(method = "renderListSeparators", at = @At("HEAD"), cancellable = true)
	private void originclient$clearListSeparators(GuiGraphics guiGraphics, CallbackInfo ci) {
		if (MenuBlur.transparent() && !OriginStyle.vanillaMenusOn(Minecraft.getInstance().screen)) {
			ci.cancel();
		}
	}
}
