package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 26.x has no AbstractWidget.getTooltip(): the Tooltip sits in `private final WidgetTooltipHolder
// tooltip` (javap 26.1.2 / 26.2 / 26.3). TitleScreenMixin reads it to mark a vanilla dock button's
// OWN tooltip (Mojang's FriendsButton) as narrated-only, like every other dock tip. If this stops
// applying, the cast in TitleScreenMixin throws inside its try and the title keeps vanilla's layout.
@Mixin(AbstractWidget.class)
public interface AbstractWidgetTooltipAccessor {
	@Accessor("tooltip")
	WidgetTooltipHolder originclient$tooltipHolder();
}
