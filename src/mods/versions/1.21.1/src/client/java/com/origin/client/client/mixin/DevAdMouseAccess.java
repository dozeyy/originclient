package com.origin.client.client.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Dev-only ad capture (OriginDevAd): feeds scripted pointer events through MouseHandler's own GLFW handlers. */
@Mixin(MouseHandler.class)
public interface DevAdMouseAccess {
	@Invoker("onPress")
	void originad$onPress(long window, int button, int action, int mods);

	@Invoker("onMove")
	void originad$onMove(long window, double x, double y);

	@Invoker("onScroll")
	void originad$onScroll(long window, double dx, double dy);

	@Accessor("mouseGrabbed")
	void originad$setGrabbed(boolean grabbed);
}
