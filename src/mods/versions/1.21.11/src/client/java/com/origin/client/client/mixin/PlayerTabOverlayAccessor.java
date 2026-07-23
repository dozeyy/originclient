package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

// Read-only access to the tab overlay's data (header/footer + the sorted, capped
// player list vanilla already computes) so GuiHudMixin can hand it to our custom
// renderer without recomputing it. Paired with PlayerTabOverlayMixin's suppression.
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {

	@Accessor("header")
	Component originclient$getHeader();

	@Accessor("footer")
	Component originclient$getFooter();

	@Invoker("getPlayerInfos")
	List<PlayerInfo> originclient$getPlayerInfos();
}
