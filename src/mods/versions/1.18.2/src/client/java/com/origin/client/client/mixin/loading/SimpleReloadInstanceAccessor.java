package com.origin.client.client.mixin.loading;

import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

// When every reload listener has finished PREPARING (worker threads), the reload
// enters its last stretch: the listeners' apply steps, run one after another on
// the render thread, each able to hold a frame for 100-400 ms. The loading mark
// settles before that stretch (MarkMotion.settle), so a long frame there shows
// nothing stopping. Field: SimpleReloadInstance.allPreparations (protected final
// CompletableFuture<Unit>, javap-verified on this module's mapped jar, the same
// back to 1.16.5). Own accessor in the required:false loading config: if the
// field moves, the instanceof check fails and the mark settles when the reload
// is done instead.
@Mixin(SimpleReloadInstance.class)
public interface SimpleReloadInstanceAccessor {
	@Accessor("allPreparations")
	CompletableFuture<Unit> originclient$allPreparations();
}
