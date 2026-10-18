package com.origin.client.client.mixin.loading;

import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// The load tracker behind vanilla's own progress bar on the world-loading
// screen (26.x replaced 1.21.1's chunk-count listener with LevelLoadTracker), so
// the Origin scene's bar shows the same real progress. Own accessor in the
// required:false loading config: if the field ever moves, this one mixin is
// skipped, the cast in LevelLoadingScreenMixin throws, and the bar sweeps.
@Mixin(LevelLoadingScreen.class)
public interface LevelLoadingScreenAccessor {
	@Accessor("loadTracker")
	LevelLoadTracker originclient$loadTracker();
}
