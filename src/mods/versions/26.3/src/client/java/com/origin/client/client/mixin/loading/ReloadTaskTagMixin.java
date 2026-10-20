package com.origin.client.client.mixin.loading;

import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.OriginFrameLog;
import com.origin.client.client.StartupTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.Executor;

// Dev frame log only (-Dorigin.frameLog=true): names and times every client
// reload listener's render-thread ("apply") step. Target: SimpleReloadInstance's
// constructor, where each listener gets its own apply executor
// (StateFactory.create(barrier, manager, listener, prepare, apply), arg 4); for
// a CLIENT reload (game executor = Minecraft; server/data-pack reloads are left
// alone) with the log on, it is wrapped to time the step. The wrap changes
// nothing about how or in which order a task runs; with the log off the
// executor is returned untouched. If the target moves, the log just loses the
// step names (required:false loading config).
// PER-VERSION (javap-verified on 26.3): from 1.21.6 the per-listener setup moved
// out of the constructor into prepareTasks(taskExecutor, mainThreadExecutor, ...),
// whose second Executor argument is the game executor and whose one
// PreparableReloadListener local is the listener; from 1.21.10 StateFactory.create
// takes (SharedState, barrier, listener, prepare, apply): the apply executor is
// still arg 4.
@Mixin(SimpleReloadInstance.class)
public abstract class ReloadTaskTagMixin {
	@ModifyArg(method = "prepareTasks", index = 4, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$SharedState;Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
	private Executor originclient$watchApplySteps(Executor apply, @Local PreparableReloadListener listener,
												 @Local(argsOnly = true, ordinal = 1) Executor gameExecutor) {
		if (!OriginFrameLog.ENABLED || !(gameExecutor instanceof Minecraft)) {
			return apply;   // log off, or a server (data pack) reload
		}
		String name = originclient$name(listener);
		return task -> apply.execute(() -> {
			StartupTransition.reloadRenderWorkRan(name);
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
