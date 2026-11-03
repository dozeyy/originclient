package com.origin.client.client.mixin;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Transparent Menus (Settings > General), the part MenuBlurMixin cannot reach: in a
// world, every vanilla list screen (options, video settings, controls, world/server
// lists, resource packs...) tiles a dark "inworld_menu_list_background" strip behind its
// rows and header/footer separator lines on top (AbstractSelectionList.extractListBackground
// / extractListSeparators). With the option on, both are skipped so the world shows through the
// whole screen. Out of a world nothing changes (MenuBlur.transparent() needs a level),
// and the option off = vanilla. Ported from 1.21.1's SelectionListTransparentMixin.
// 26.x renamed them from render* (javap of 26.3: extractListBackground(GuiGraphicsExtractor)).
// Handlers take only the CallbackInfo, so they bind by NAME whatever the draw-context
// parameter is; defaultRequire 0: if a name moves, those screens keep vanilla's dark
// list strip, nothing breaks.
@Mixin(AbstractSelectionList.class)
public class SelectionListTransparentMixin {

	@Inject(method = "extractListBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$clearListBackground(CallbackInfo ci) {
		if (MenuBlur.transparent() && !OriginStyle.vanillaMenusOn(Minecraft.getInstance().screen)) {
			ci.cancel();
		}
	}

	@Inject(method = "extractListSeparators", at = @At("HEAD"), cancellable = true)
	private void originclient$clearListSeparators(CallbackInfo ci) {
		if (MenuBlur.transparent() && !OriginStyle.vanillaMenusOn(Minecraft.getInstance().screen)) {
			ci.cancel();
		}
	}
}
