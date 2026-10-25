package com.origin.client.client.mixin.loading;

import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// The stage line and percentage vanilla's ProgressScreen keeps ("Saving chunks",
// "Building terrain" ... and 0-100), so the Origin loading scene shows the real
// stage and its real progress. Own accessor in the required:false loading config:
// if a field moves, ProgressScreenMixin's cast throws and it falls back to the
// screen's title with a sweeping rail.
@Mixin(ProgressScreen.class)
public interface ProgressScreenAccessor {
	@Accessor("header")
	Component originclient$header();

	@Accessor("stage")
	Component originclient$stage();

	@Accessor("progress")
	int originclient$progress();
}
