package com.origin.client.client.mixin.world;

import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.model.IBakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Glint Colorizer replays RenderItem.renderEffect's two glint passes, which draw
// the model through the private renderModel(IBakedModel, int colour). An
// @Invoker (not a @Shadow inside GlintColorizerMixin) so that if OptiFine's
// replacement RenderItem ever lacks it, only this accessor fails to apply and
// the glint falls back to vanilla -- the mixin checks `instanceof` first.
@Mixin(RenderItem.class)
public interface RenderItemInvoker {
	@Invoker("renderModel")
	void origin$renderModel(IBakedModel model, int color);
}
