// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.api.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Iris's PBR mixin into vanilla SpriteContents marks a sprite as used so Sodium animates it. Without
 * Sodium, vanilla (and VulkanMod) animate every sprite anyway, so marking is a no-op.
 */
public interface SpriteUtil {
	SpriteUtil INSTANCE = sprite -> { };

	void markSpriteActive(TextureAtlasSprite sprite);
}
