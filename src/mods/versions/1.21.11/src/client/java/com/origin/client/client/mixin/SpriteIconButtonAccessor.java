package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.WidgetSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Reads the icon geometry off SpriteIconButton. The fields (sprite, spriteWidth,
// spriteHeight) are declared on THIS class, so the accessor mixin targets it
// directly -- unlike a @Shadow from the CenteredIcon/TextAndIcon subclasses,
// which Mixin rejects as an unresolvable ("remappable") inherited-field shadow.
// SpriteIconButtonMixin casts the widget to this to hand the values to the
// Frost renderer.
//
// PER-VERSION DELTA (1.21.11): `sprite` is now WidgetSprites (bundles the
// enabled/disabled/focused variants), not a single ResourceLocation/Identifier
// like 1.21.1 -- the caller resolves the right variant via
// WidgetSprites.get(active, hoveredOrFocused) at draw time.
@Mixin(SpriteIconButton.class)
public interface SpriteIconButtonAccessor {
	@Accessor("sprite")
	WidgetSprites originclient$sprite();

	@Accessor("spriteWidth")
	int originclient$spriteWidth();

	@Accessor("spriteHeight")
	int originclient$spriteHeight();
}
