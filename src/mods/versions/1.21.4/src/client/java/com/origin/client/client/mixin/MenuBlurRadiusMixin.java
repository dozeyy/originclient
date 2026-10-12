package com.origin.client.client.mixin;

import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Menu Blur's strength. PER-VERSION (1.21.2-1.21.4, verified against the
// decompiled 1.21.4 GameRenderer): there is no blurEffect field to drive as on
// 1.21.1 -- processBlurEffect() reads the Menu Background Blurriness option into
// its first float local, then looks the "blur" post chain up and runs it with
// that radius. While MenuBlur.blur() is calling it, that local is swapped for
// Origin's strength; every other caller (vanilla's own menus, Origin's title
// backdrop) gets the option as before -- radiusOverride returns it untouched.
@Mixin(GameRenderer.class)
public class MenuBlurRadiusMixin {
	@ModifyVariable(method = "processBlurEffect", at = @At("STORE"), ordinal = 0)
	private float originclient$menuBlurRadius(float radius) {
		return MenuBlur.radiusOverride(radius);
	}
}
