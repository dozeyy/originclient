package com.origin.client.client.mixin.loading;

import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// The load tracker behind vanilla's progress bar on the world-loading screen,
// so the Origin scene's bar shows the same real progress. Own accessor in the
// required:false loading config: if the field ever moves, this one mixin is
// skipped, the cast in LevelLoadingScreenMixin throws, and the bar sweeps.
//
// PER-VERSION (1.21.9+): the screen was rebuilt around LevelLoadTracker (it
// replaced 1.21.1's StoringChunkProgressListener and also covers the old
// "receiving level" phase), held in a non-final loadTracker field.
@Mixin(LevelLoadingScreen.class)
public interface LevelLoadingScreenAccessor {
	@Accessor("loadTracker")
	LevelLoadTracker originclient$loadTracker();
}
