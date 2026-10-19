package com.origin.client.client.mixin.loading;

import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Dev-only (-Dorigin.bootLog.reloadProfile=true, profiling runs of
// .github/workflows/boot-time.yml): makes every resource reload a vanilla
// ProfiledReloadInstance, which logs each reload listener's prepare and apply
// time ("X took approximately N ms (P ms preparing, A ms applying)"). Vanilla
// only does that with debug logging on. Target: ReloadableResourceManager.createReload's
// SimpleReloadInstance.create call, its "profiled" flag. Off = vanilla's value.
@Mixin(ReloadableResourceManager.class)
public abstract class BootLogReloadMixin {
	@Unique
	private static final boolean PROFILE = Boolean.getBoolean("origin.bootLog.reloadProfile");

	@ModifyArg(method = "createReload", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance;create(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Z)Lnet/minecraft/server/packs/resources/ReloadInstance;"),
			index = 5)
	private boolean originclient$profileReload(boolean profiled) {
		return profiled || PROFILE;
	}
}
