package com.origin.client.client.mixin;

import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

// Target: PackSelectionScreen.model / reload(). Why: the Resource Pack Hub opens
// on top of Minecraft's own pack screen and, when it closes, writes its enable/
// reorder edits into that screen's (uncommitted) selection, so vanilla's Done
// still commits everything in one reload and the two lists can never disagree.
// If this stops applying, the hub detects it (ClassCastException) and runs
// install-only: enabling and ordering then happen in vanilla's list as usual.
@Mixin(PackSelectionScreen.class)
public interface PackSelectionScreenAccessor {
	@Accessor("model")
	PackSelectionModel originclient$model();

	/** Rescans resourcepacks/ (new installs, deleted files) and repopulates both lists. */
	@Invoker("reload")
	void originclient$reload();
}
