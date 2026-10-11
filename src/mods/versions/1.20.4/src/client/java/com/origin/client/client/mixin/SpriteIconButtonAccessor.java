package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Reads the icon geometry off SpriteIconButton. The fields (sprite, spriteWidth,
// spriteHeight) are declared on THIS class, so the accessor mixin targets it
// directly -- unlike a @Shadow from the CenteredIcon/TextAndIcon subclasses,
// which Mixin rejects as an unresolvable ("remappable") inherited-field shadow.
// SpriteIconButtonMixin casts the widget to this to hand the values to the
// Frost renderer.
@Mixin(SpriteIconButton.class)
public interface SpriteIconButtonAccessor {
	@Accessor("sprite")
	ResourceLocation originclient$sprite();

	@Accessor("spriteWidth")
	int originclient$spriteWidth();

	@Accessor("spriteHeight")
	int originclient$spriteHeight();
}
