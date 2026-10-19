package com.origin.client.client.mixin.loading;

import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.OriginFrameLog;
import com.origin.client.client.StartupTransition;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.Executor;

// Watches every reload listener's render-thread ("apply") executor. Target:
// SimpleReloadInstance's constructor, where each listener gets its own apply
// executor (StateFactory.create(barrier, manager, listener, prepare, apply),
// arg 4); it is wrapped so that:
//  - the loading mark knows the moment render-thread reload work begins
//    (StartupTransition.reloadRenderWork), the stretch where a single step can
//    hold a frame for hundreds of ms. Not only after every listener prepared:
//    some mods run heavy work on this executor during prepare (TACZ's
//    bedrockmodelloader, 1.35 s on CI, 2026-10-18), so allPreparations alone
//    came too late;
//  - with the dev frame log on (-Dorigin.frameLog=true), each step is timed and
//    named by its listener.
// The wrap is one lambda per task and changes nothing about how or when a task
// runs. If the target moves, only these two signals are lost (required:false
// loading config); the mark then settles once preparations finish or the load is done.
@Mixin(SimpleReloadInstance.class)
public abstract class ReloadTaskTagMixin {
	@ModifyArg(method = "<init>", index = 4, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
	private Executor originclient$watchApplySteps(Executor apply, @Local PreparableReloadListener listener) {
		Object self = this;
		String name = OriginFrameLog.ENABLED ? originclient$name(listener) : null;
		return task -> apply.execute(() -> {
			StartupTransition.reloadRenderWork(self, name);
			if (name == null) {
				task.run();
				return;
			}
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
