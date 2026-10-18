package com.origin.client.client.mixin.menus;

import net.minecraft.client.gui.GuiScreenResourcePacks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// The Resource Pack Hub's link to Minecraft's Resource Packs screen. 1.8.9: the
// screen keeps its own copy of the pack lists and rebuilds them from the
// repository on re-init ONLY while `changed` is false. So the hub can apply its
// edits straight to the repository (as the screen's Done does) and the screen
// shows them on return -- as long as the player has no unsaved edits of their
// own in it. 1.21.1 instead reaches into PackSelectionModel (no such model here).
// NAME UNVERIFIED (tools/ref-queries/packhub.txt): if `changed` doesn't apply,
// the cast fails, ResourcePackHubScreen catches it and runs install-only.
@Mixin(GuiScreenResourcePacks.class)
public interface GuiScreenResourcePacksAccessor {
	@Accessor("changed")
	boolean originclient$changed();
}
