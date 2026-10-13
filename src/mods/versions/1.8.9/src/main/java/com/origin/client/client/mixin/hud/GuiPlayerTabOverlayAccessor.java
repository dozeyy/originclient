package com.origin.client.client.mixin.hud;

import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Read-only access to the tab overlay's header/footer (both private on 1.8.9, no
// getters -- only setHeader/setFooter) so HudHooks can hand them to OriginTabList.
// Paired with HudHooks' Pre(PLAYER_LIST) cancel, which only suppresses vanilla's
// list while this accessor applied -- otherwise vanilla's list stays.
@Mixin(GuiPlayerTabOverlay.class)
public interface GuiPlayerTabOverlayAccessor {

	@Accessor("header")
	IChatComponent originclient$getHeader();

	@Accessor("footer")
	IChatComponent originclient$getFooter();
}
