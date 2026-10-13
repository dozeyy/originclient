package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.CycleButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Reads CycleButton's two private draw switches so AbstractButtonMixin can ask
// the same questions vanilla's draw method asks before it paints:
//   - spriteSupplier: a non-null sprite for the current value means vanilla
//     blits art (the recipe book's craftable filter) -> keep vanilla.
//   - displayState: HIDE means vanilla draws no label at all -> keep vanilla
//     (an Origin shell would print text vanilla deliberately hides).
// Everything else is a text toggle ("Something: Value" in the Options menus)
// and wears the Origin look.
// 1.21.11+ only: pre-1.21.11 that filter was a StateSwitchingButton, which is
// not an AbstractButton at all and never reached the Origin restyle.
// If a field is renamed, the accessor fails at mixin apply (boot error), not
// silently.
@Mixin(CycleButton.class)
public interface CycleButtonSpriteAccessor {

	@Accessor("spriteSupplier")
	CycleButton.SpriteSupplier<?> originclient$spriteSupplier();

	@Accessor("displayState")
	CycleButton.DisplayState originclient$displayState();
}
