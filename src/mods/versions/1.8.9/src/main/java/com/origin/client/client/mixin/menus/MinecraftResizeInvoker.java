package com.origin.client.client.mixin.menus;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// B189PRE-1: StartupLoading.finish() hands the title the window's REAL size when the player
// resized or maximised it during the startup. Minecraft only picks a resize up in
// checkWindowResize (Display.wasResized(), true for ONE Display.update), and the startup
// screen's own Display.update calls spend that flag first. resize(int, int) is vanilla's own
// private step for it (displayWidth/Height, the screen's onResize, the loading-screen renderer,
// framebufferMc). An @Invoker of its own, so if OptiFine's Minecraft ever lacked it only this
// fails to apply and finish() skips the call (it checks `instanceof` first).
@Mixin(Minecraft.class)
public interface MinecraftResizeInvoker {
	@Invoker("resize")
	void origin$resize(int width, int height);
}
