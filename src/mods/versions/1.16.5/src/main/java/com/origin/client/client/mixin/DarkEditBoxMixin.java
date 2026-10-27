package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginDarkWidgets;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

// Target: EditBox.renderButton, the two frame fill colours (each appears once in the method):
// -6250336 (0xFFA0A0A0, the unfocused edge) and -16777216 (0xFF000000, the well). The focused
// edge (-1, white) and every text / cursor colour are left alone.
// Why: 1.21.1's dark text_field (well #0E0E0F, edge #505055; OriginDarkWidgets).
// If it stops applying: text fields keep vanilla's grey edge on black (no crash).
@Mixin(EditBox.class)
public abstract class DarkEditBoxMixin {
	@ModifyConstant(method = "renderButton", constant = @Constant(intValue = -6250336))
	private int originclient$darkEdge(int vanilla) {
		return OriginDarkWidgets.colour(vanilla, OriginDarkWidgets.FIELD_EDGE, OriginDarkWidgets.EDIT_EDGE);
	}

	@ModifyConstant(method = "renderButton", constant = @Constant(intValue = -16777216))
	private int originclient$darkWell(int vanilla) {
		return OriginDarkWidgets.colour(vanilla, OriginDarkWidgets.FIELD, OriginDarkWidgets.EDIT_FIELD);
	}
}
