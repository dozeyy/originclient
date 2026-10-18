package com.origin.client.client.mixin;

import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlTessellation;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

// Target: Sodium 0.5.11's (1.21 catalog pin) DefaultChunkRenderer shared index buffer + per-region
// tessellation (the vertex array Sodium itself draws each region with). Why:
// Origin Render draws the same geometry with the same vertex array, only the
// draw call differs (GPU-written indirect commands). Missing -> stock Sodium.
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public interface SodiumDefaultChunkRendererAccessor {
	@Accessor("sharedIndexBuffer")
	SharedQuadIndexBuffer originclient$sharedIndexBuffer();

	@Invoker("prepareTessellation")
	GlTessellation originclient$prepareTessellation(CommandList commandList, RenderRegion region);
}
