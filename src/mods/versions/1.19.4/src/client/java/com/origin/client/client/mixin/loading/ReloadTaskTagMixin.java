package com.origin.client.client.mixin.loading;

import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.OriginFrameLog;
import com.origin.client.client.StartupTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.Executor;

// Watches every reload listener's render-thread ("apply") executor. Target:
// SimpleReloadInstance's constructor (Executor background, Executor game,
// ResourceManager, List, StateFactory, CompletableFuture), where each listener
// gets its own apply executor (StateFactory.create(barrier, manager, listener,
// prepare, apply), arg 4); javap-verified on this module's mapped jar: the same
// constructor and create descriptor as 1.21.1, back to 1.16.5. For a CLIENT
// reload (game executor = Minecraft; server/data-pack reloads are left alone)
// it is wrapped so that:
//  - the moment a task is QUEUED for the render thread, the loading mark is told
//    (StartupTransition.reloadRenderWorkQueued) and starts its short settle,
//    while ReloadTaskBudgetMixin holds the queue until the mark is at rest; so
//    no long step (fonts, Shader Loader, a mod's atlas) can freeze the mark
//    mid-motion;
//  - with the dev frame log on (-Dorigin.frameLog=true), each step is timed and
//    named by its listener.
// The wrap changes nothing about how or in which order a task runs. If the
// target moves, both signals are lost (required:false loading config); the mark
// then settles once preparations finish or the load is done.
@Mixin(SimpleReloadInstance.class)
public abstract class ReloadTaskTagMixin {
	@ModifyArg(method = "<init>", index = 4, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
	private Executor originclient$watchApplySteps(Executor apply, @Local PreparableReloadListener listener,
												 @Local(argsOnly = true, ordinal = 1) Executor gameExecutor) {
		if (!(gameExecutor instanceof Minecraft)) {
			return apply;   // a server (data pack) reload: never on the loading screen's thread
		}
		final String name = OriginFrameLog.ENABLED ? originclient$name(listener) : null;
		return task -> {
			StartupTransition.reloadRenderWorkQueued();
			final Runnable step = name == null ? task : () -> {
				StartupTransition.reloadRenderWorkRan(name);
				long t0 = System.nanoTime();
				try {
					task.run();
				} finally {
					OriginFrameLog.reloadTask(name, (System.nanoTime() - t0) / 1e6);
				}
			};
			apply.execute(step);
		};
	}

	@Unique
	private static String originclient$name(PreparableReloadListener listener) {
		try {
			if (listener instanceof net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener) {
				return ((net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener) listener).getFabricId().toString();
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
