package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.WidgetSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Reads the icon geometry off SpriteIconButton. The fields are declared on THIS
// class, so the accessor targets it directly — a @Shadow from the
// CenteredIcon/TextAndIcon subclasses is rejected by Mixin.
@Mixin(SpriteIconButton.class)
public interface SpriteIconButtonAccessor {
	@Accessor("sprite")
	WidgetSprites originclient$sprite();

	@Accessor("spriteWidth")
	int originclient$spriteWidth();

	@Accessor("spriteHeight")
	int originclient$spriteHeight();
}
