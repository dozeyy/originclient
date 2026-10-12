package com.origin.client.client.mixin;

import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Menu Blur strength. On 1.21.10 (GameRenderer / GlobalSettingsUniform source-
// verified) the menu blur radius is not passed to the blur pass: GameRenderer.
// render(DeltaTracker, boolean) writes options.getMenuBackgroundBlurriness()
// into the global settings uniform block (6th update() argument, the int
// MenuBlurRadius the "blur" post chain reads) once per frame, before the GUI
// draws. While Menu Blur owns the open screen, its strength goes there instead;
// otherwise the player's own option passes through untouched. (1.21.10's
// update() is the 6-argument form; 1.21.11 appended the camera + a flag.)
@Mixin(GameRenderer.class)
public class MenuBlurRadiusMixin {
	@ModifyArg(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJLnet/minecraft/client/DeltaTracker;I)V"),
			index = 5)
	private int originclient$menuBlurRadius(int vanilla) {
		try {
			return MenuBlur.handles(Minecraft.getInstance().screen) ? MenuBlur.radius() : vanilla;
		} catch (Throwable t) {
			return vanilla;
		}
	}
}
