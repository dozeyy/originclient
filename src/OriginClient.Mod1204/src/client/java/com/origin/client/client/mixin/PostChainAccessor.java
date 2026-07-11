package com.origin.client.client.mixin;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

// 1.20's PostChain has no setUniform (that arrived in a later version). Motion
// Blur reaches the private pass list and sets the "Amount" uniform on each pass
// itself, matching what the newer setUniform does internally.
@Mixin(PostChain.class)
public interface PostChainAccessor {
	@Accessor("passes")
	List<PostPass> originclient$passes();
}
