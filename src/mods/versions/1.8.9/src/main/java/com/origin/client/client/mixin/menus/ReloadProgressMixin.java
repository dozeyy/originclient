package com.origin.client.client.mixin.menus;

import com.origin.client.client.render.ReloadOverlay;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// Target: SimpleReloadableResourceManager.notifyReloadListeners -- the loop that runs
// every reload listener (textures, models, sounds, fonts, language...) for each resource
// reload (Minecraft.refreshResources: packs, language, mipmaps, F3+T, the Pack Hub).
// Why: 1.8.9 has no reload screen; ReloadOverlay draws Origin's loading picture at the
// start, before each listener (the bar = listeners finished / all) and at the end.
// HEAD / INVOKE-before / RETURN only: nothing of vanilla's loop changes. Not an OptiFine
// class. ReloadOverlay itself keeps out of the startup reload. If this stops applying,
// reloads freeze the picture as vanilla 1.8.9 always did.
@Mixin(SimpleReloadableResourceManager.class)
public abstract class ReloadProgressMixin {
	@Shadow
	@Final
	private List<IResourceManagerReloadListener> reloadListeners;

	@Inject(method = "notifyReloadListeners", at = @At("HEAD"))
	private void originclient$reloadBegin(CallbackInfo ci) {
		try {
			ReloadOverlay.begin(reloadListeners == null ? 1 : reloadListeners.size());
		} catch (Throwable t) {
			// no overlay this reload
		}
	}

	@Inject(method = "notifyReloadListeners", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/resources/IResourceManagerReloadListener;onResourceManagerReload(Lnet/minecraft/client/resources/IResourceManager;)V"))
	private void originclient$reloadStep(CallbackInfo ci) {
		try {
			ReloadOverlay.step();
		} catch (Throwable t) {
			// the frame stays as it was
		}
	}

	@Inject(method = "notifyReloadListeners", at = @At("RETURN"))
	private void originclient$reloadEnd(CallbackInfo ci) {
		try {
			ReloadOverlay.end();
		} catch (Throwable t) {
			// the next game frame replaces it anyway
		}
	}
}
