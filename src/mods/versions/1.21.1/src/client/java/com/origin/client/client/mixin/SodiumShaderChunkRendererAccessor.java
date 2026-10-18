package com.origin.client.client.mixin;

import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

// Target: Sodium 0.6.13's ShaderChunkRenderer begin/end + activeProgram. Why:
// Origin Render (render/terrain/OriginRender) replaces only the draw loop of a
// terrain pass and must set up and tear down exactly as Sodium does, so begin()
// still runs Iris's hooks and binds the same program, textures and fog. If this
// stops applying, OriginRender sees no accessor and leaves Sodium alone.
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public interface SodiumShaderChunkRendererAccessor {
	@Invoker("begin")
	void originclient$begin(TerrainRenderPass pass);

	@Invoker("end")
	void originclient$end(TerrainRenderPass pass);

	@Accessor("activeProgram")
	GlProgram<ChunkShaderInterface> originclient$activeProgram();
}
