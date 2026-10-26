package com.origin.client.client.mixin;

import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

// Target: Sodium 0.6.13's DefaultChunkRenderer shared index buffer + per-region
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
