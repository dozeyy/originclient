package com.origin.client.client.mixin;

import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Menu Blur strength. On 1.21.11 (GameRenderer / GlobalSettingsUniform source-
// verified) the menu blur radius is not passed to the blur pass: GameRenderer.
// render(DeltaTracker, boolean) writes options.getMenuBackgroundBlurriness()
// into the global settings uniform block (6th update() argument, the int
// MenuBlurRadius the "blur" post chain reads) once per frame, before the GUI
// draws. While Menu Blur owns the open screen, its strength goes there instead;
// otherwise the player's own option passes through untouched.
@Mixin(GameRenderer.class)
public class MenuBlurRadiusMixin {
	// 26.2: the camera argument became its position (Vec3); the frame is split into
	// extract() + render(), so both are named and whichever calls update() binds.
	@ModifyArg(method = {"render(Lnet/minecraft/client/DeltaTracker;Z)V", "extract(Lnet/minecraft/client/DeltaTracker;Z)V"},
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJLnet/minecraft/client/DeltaTracker;ILnet/minecraft/world/phys/Vec3;Z)V"),
			index = 5)
	private int originclient$menuBlurRadius(int vanilla) {
		try {
			return MenuBlur.handles(Minecraft.getInstance().gui.screen()) ? MenuBlur.radius() : vanilla;
		} catch (Throwable t) {
			return vanilla;
		}
	}
}
