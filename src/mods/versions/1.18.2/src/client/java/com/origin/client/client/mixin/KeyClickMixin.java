package com.origin.client.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.mods.ClickStats;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// CPS counter + Keystrokes: every press the game turns into a keybind click
// (MouseHandler.onPress / KeyboardHandler.keyPress call KeyMapping.click, in the
// world only); ClickStats keeps the ones bound to Attack / Use. Replaces the old
// raw mouse-button hook in MouseHandlerMixin, which counted buttons 0/1 whatever
// they were bound to. If this stops applying, CPS reads 0; nothing else breaks.
@Mixin(KeyMapping.class)
public class KeyClickMixin {

	@Inject(method = "click", at = @At("HEAD"))
	private static void originclient$countClick(InputConstants.Key key, CallbackInfo ci) {
		ClickStats.onClick(key);
	}
}
