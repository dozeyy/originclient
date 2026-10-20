package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.origin.client.client.gui.MenuText;
import com.origin.client.client.gui.OriginSurface;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Target: EditBox.renderWidget(GuiGraphics,int,int,float) -> its one
// GuiGraphics.blitSprite(ResourceLocation,IIII) call, the bordered frame
// (javap, mapped 1.21.1: SPRITES.get(isActive(), isFocused()) then blitSprite
// at getX/getY/getWidth/getHeight, before the text and cursor are drawn).
// Why: inside Minecraft's own menus (MenuTextScopeMixin) vanilla's stark black
// box with a white outline sat next to the clean glass buttons (world search,
// world name -- CI shot 2026-10-19). There it draws as a darker inset glass
// well instead; focus brightens the hairline. Text, cursor, selection and
// suggestion are vanilla's, drawn over it unchanged.
// If this stops applying: text fields keep vanilla's black box.
@Mixin(EditBox.class)
public abstract class EditBoxFrameMixin {

	@WrapOperation(method = "renderWidget", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
	private void originclient$cleanFrame(GuiGraphics g, ResourceLocation sprite, int x, int y, int w, int h,
										 Operation<Void> original) {
		if (!MenuText.inScope()) {
			original.call(g, sprite, x, y, w, h);
			return;
		}
		try {
			EditBox self = (EditBox) (Object) this;
			boolean active = self.isActive();
			int border = !active ? OriginTheme.CLEAN_LINE_OFF
					: self.isFocused() ? OriginTheme.CLEAN_LINE_ACCENT
					: self.isHovered() ? OriginTheme.CLEAN_LINE_HI : OriginTheme.CLEAN_LINE;
			OriginSurface.draw(g, x, y, w, h, active ? OriginTheme.CLEAN_FIELD : OriginTheme.CLEAN_GLASS_OFF, border, 0.5f);
		} catch (Throwable t) {
			original.call(g, sprite, x, y, w, h);
		}
	}
}
