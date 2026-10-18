package com.origin.client.client.mixin.menus;

import com.origin.client.client.render.OriginLoadingRenderer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The world-loading scene's real progress for singleplayer (1.21.1 reads
// LevelLoadingScreen's chunk count). 1.8.9: the integrated server prepares the
// spawn area on its own thread and reports it only through
// outputPercentRemaining("Preparing spawn area", percent) -- the client's wait
// loop passes the loading screen just the stage text, never the percent.
// HEAD capture of the argument: no field names involved, and MinecraftServer is
// not a class OptiFine replaces. A miss (method not found) only leaves the bar
// sweeping (defaultRequire 0).
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerProgressMixin {

	@Inject(method = "outputPercentRemaining", at = @At("HEAD"))
	private void originclient$spawnProgress(String message, int percent, CallbackInfo ci) {
		OriginLoadingRenderer.serverProgress(percent);
	}
}
