package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Read-only access to the tab overlay's header/footer so GuiHudMixin can hand them
// to our custom renderer. Paired with PlayerTabOverlayMixin's suppression. Up to and including
// 1.19.3 there is no getPlayerInfos() to invoke (vanilla sorts inline in render()),
// so the player list comes from OriginTabList.vanillaPlayerList instead.
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {

	@Accessor("header")
	Component originclient$getHeader();

	@Accessor("footer")
	Component originclient$getFooter();
}
