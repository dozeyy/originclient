package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// AbstractWidget has setWidth but no setHeight on this version (1.21.1 has both).
// The title layout shrinks the main-menu buttons to 17px tall, so this exposes the
// protected `height` field. If the field is ever renamed the accessor fails to
// apply and the title mixin's layout throws into its fail-soft catch — vanilla's
// own layout stays.
@Mixin(AbstractWidget.class)
public interface AbstractWidgetAccessor {
	@Accessor("height")
	void originclient$setHeight(int height);
}
