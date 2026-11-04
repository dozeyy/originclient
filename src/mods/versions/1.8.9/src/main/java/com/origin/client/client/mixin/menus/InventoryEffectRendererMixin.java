package com.origin.client.client.mixin.menus;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.InventoryEffectRenderer;
import net.minecraft.inventory.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: InventoryEffectRenderer.updateActivePotionEffects (the survival AND creative
// inventory; GuiContainer.initGui centres guiLeft, then this moves it).
// Why: with any potion effect vanilla 1.8.9 pushes the inventory 60 GUI px right of centre
// (guiLeft = 160 + (width - xSize - 200) / 2) to make room for the effect list on its left,
// so every slot moves whenever an effect starts or ends -- muscle memory breaks mid-fight.
// 1.21.1's inventory stays centred; so does this one (Patcher's fix): guiLeft is put back
// to the centre at RETURN, and the effect list keeps drawing at guiLeft - 124 on its left.
// Only when that list still fits on screen (centre >= 124 px from the left edge, true at
// every common size and GUI scale): on a narrower screen vanilla's shift stays, so the
// effects are never cut off.
// The mixin extends GuiContainer so guiLeft / xSize / width are inherited members (no
// @Shadow across the hierarchy). InventoryEffectRenderer is not an OptiFine class; RETURN
// only anyway. If it stops applying, the inventory shifts as in vanilla.
@Mixin(InventoryEffectRenderer.class)
public abstract class InventoryEffectRendererMixin extends GuiContainer {
	/** InventoryEffectRenderer draws its effect boxes from guiLeft - 124. */
	private static final int EFFECTS_W = 124;

	private InventoryEffectRendererMixin(Container container) {
		super(container);
	}

	@Inject(method = "updateActivePotionEffects", at = @At("RETURN"))
	private void originclient$keepCentred(CallbackInfo ci) {
		try {
			int centre = (this.width - this.xSize) / 2;
			if (centre >= EFFECTS_W) {
				this.guiLeft = centre;
			}
		} catch (Throwable t) {
			// vanilla's position
		}
	}
}
