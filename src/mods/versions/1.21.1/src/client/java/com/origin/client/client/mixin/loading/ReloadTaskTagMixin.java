package com.origin.client.client.mixin.loading;

import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.OriginFrameLog;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.Executor;

// Dev-only (OriginFrameLog, -Dorigin.frameLog=true): names the reload listener
// behind every render-thread apply step, so a slow frame says WHICH listener
// (ModelManager, SoundManager, a mod's...) instead of "a CompletableFuture".
// Target: SimpleReloadInstance's constructor, where each listener gets its own
// apply executor (StateFactory.create(barrier, manager, listener, prepare,
// apply), arg 4); with the logger on, that executor is wrapped to time each
// step. With it off the original executor is passed through untouched. If the
// target moves, steps are simply unnamed (required:false loading config).
@Mixin(SimpleReloadInstance.class)
public abstract class ReloadTaskTagMixin {
	@ModifyArg(method = "<init>", index = 4, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
	private Executor originclient$tagApplySteps(Executor apply, @Local PreparableReloadListener listener) {
		if (!OriginFrameLog.ENABLED) {
			return apply;
		}
		String name = originclient$name(listener);
		return task -> apply.execute(() -> {
			long t0 = System.nanoTime();
			try {
				task.run();
			} finally {
				OriginFrameLog.reloadTask(name, (System.nanoTime() - t0) / 1e6);
			}
		});
	}

	private static String originclient$name(PreparableReloadListener listener) {
		try {
			if (listener instanceof net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener id) {
				return id.getFabricId().toString();
			}
			String n = listener.getName();
			if (n != null && !n.isEmpty()) {
				return n;
			}
		} catch (Throwable ignored) {
		}
		return listener.getClass().getName();
	}
}
