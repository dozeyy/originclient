package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.CycleButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Reads CycleButton's private sprite supplier so AbstractButtonMixin can tell
// the two kinds of cycle button apart:
//   • text cycle buttons ("Something: Value" in the Options menus) -> Origin look
//   • sprite cycle buttons (the recipe book's craftable filter, whose label is
//     DisplayState.HIDE) -> vanilla art, or it renders as an empty Origin box
//     with the hidden label ("Showing All") sprawling out of it.
// 1.21.11 only: pre-1.21.11 that filter was a StateSwitchingButton, which is
// not an AbstractButton at all and never reached the Origin restyle.
@Mixin(CycleButton.class)
public interface CycleButtonSpriteAccessor {

	@Accessor("spriteSupplier")
	CycleButton.SpriteSupplier<?> originclient$spriteSupplier();
}
