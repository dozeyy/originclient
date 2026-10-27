package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginDarkWidgets;
import net.minecraft.client.gui.components.LockIconButton;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Target: LockIconButton.renderWidget, its widgets.png texture id (icons at u 0/20, v 146/166/186).
// Why: Origin's dark lock button (dark_widgets.png has it at those UVs; OriginDarkWidgets).
// If it stops applying: the difficulty lock draws vanilla's light button (no crash).
@Mixin(LockIconButton.class)
public abstract class DarkLockButtonMixin {
	@ModifyArg(method = "renderWidget", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"))
	private ResourceLocation originclient$darkLock(ResourceLocation vanilla) {
		return OriginDarkWidgets.texture(vanilla, OriginDarkWidgets.WIDGETS, OriginDarkWidgets.LOCK);
	}
}
