package com.origin.client.client.mixin.loading;

import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// The chunk counter behind vanilla's "NN%" on the singleplayer loading screen,
// so the Origin scene's bar shows the same real progress. Own accessor in the
// required:false loading config: if the field ever moves, this one mixin is
// skipped, the cast in LevelLoadingScreenMixin throws, and the bar sweeps.
@Mixin(LevelLoadingScreen.class)
public interface LevelLoadingScreenAccessor {
	@Accessor("progressListener")
	StoringChunkProgressListener originclient$progressListener();
}
