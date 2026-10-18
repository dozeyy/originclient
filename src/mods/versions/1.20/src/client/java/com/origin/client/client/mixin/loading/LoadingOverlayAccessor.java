package com.origin.client.client.mixin.loading;

import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// The startup reload itself, so the Origin loading bar can glide to its real
// fraction done on a clock (OriginScreenRenderer.reloadProgress) instead of
// vanilla's currentProgress, which is smoothed per FRAME — it crawls when frames
// are slow and never reaches full before the screen dissolves. Own accessor in
// the required:false loading config: if the field ever moves, this one mixin is
// skipped, the instanceof check fails, and the bar uses currentProgress.
@Mixin(LoadingOverlay.class)
public interface LoadingOverlayAccessor {
	@Accessor("reload")
	ReloadInstance originclient$reload();
}
