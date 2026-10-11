package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// AbstractWidget has setWidth but no reliable setHeight in the 1.20.x line (1.21.1
// has both), so the title layout writes the protected `height` field through this
// accessor instead. If the field is ever renamed the accessor fails to apply and
// the title mixin's layout throws into its fail-soft catch — vanilla's own layout
// stays.
@Mixin(AbstractWidget.class)
public interface AbstractWidgetAccessor {
	@Accessor("height")
	void originclient$setHeight(int height);
}
