package com.origin.client.client.mixin;

import com.origin.client.client.render.terrain.OriginRender;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Origin Render's entry: every Sodium terrain pass asks OriginRender first. It
// returns true only when it drew the whole pass itself (setting on, opaque pass,
// no shaderpack, GL 4.5, no earlier failure); otherwise Sodium draws as usual.
// require = 0 (config default): if Sodium renames render(), this is a no-op.
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class SodiumChunkRendererMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
	private void originclient$originRender(ChunkRenderMatrices matrices, CommandList commandList,
			ChunkRenderListIterable renderLists, TerrainRenderPass pass, CameraTransform camera, CallbackInfo ci) {
		if (OriginRender.render((DefaultChunkRenderer) (Object) this, matrices, commandList, renderLists, pass, camera)) {
			ci.cancel();
		}
	}
}
