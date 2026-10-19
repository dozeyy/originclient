package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginBootLog;
import com.origin.client.client.OriginBootWorld;
import com.origin.client.client.StartupTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.main.GameConfig;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Dev-only boot timeline (OriginBootLog, -Dorigin.bootLog=true): marks the
// milestones between the JVM starting and an interactive title screen. Targets:
// Minecraft.<init> (Fabric runs every mod's entrypoints right after
// gameDirectory is stored, i.e. before the first File.toPath; then the window;
// then the startup resource reload is created) and Minecraft.runTick HEAD (one
// call per frame). Every handler is one static-final check when the log is off;
// if a target moves only that mark is lost (required:false loading config).
@Mixin(Minecraft.class)
public abstract class BootLogMixin {
	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/io/File;toPath()Ljava/nio/file/Path;", ordinal = 0))
	private void originclient$bootEntrypoints(GameConfig config, CallbackInfo ci) {
		OriginBootLog.mark("entrypoints-done");
	}

	@Inject(method = "<init>", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/VirtualScreen;newWindow(Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;Ljava/lang/String;)Lcom/mojang/blaze3d/platform/Window;",
			shift = At.Shift.AFTER))
	private void originclient$bootWindow(GameConfig config, CallbackInfo ci) {
		OriginBootLog.mark("window-created");
	}

	@Inject(method = "<init>", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;"))
	private void originclient$bootReloadStart(GameConfig config, CallbackInfo ci) {
		OriginBootLog.mark("reload-start");
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void originclient$bootCtorDone(GameConfig config, CallbackInfo ci) {
		OriginBootLog.mark("ctor-done");
	}

	@Inject(method = "runTick", at = @At("HEAD"))
	private void originclient$bootFrame(boolean renderLevel, CallbackInfo ci) {
		if (!OriginBootLog.ENABLED) {
			return;
		}
		Minecraft mc = (Minecraft) (Object) this;
		OriginBootLog.mark("first-frame");
		if (mc.getOverlay() instanceof LoadingOverlay overlay) {
			ReloadInstance reload = ((LoadingOverlayAccessor) overlay).originclient$reload();
			if (reload != null && reload.isDone()) {
				OriginBootLog.mark("reload-done");
			}
		}
		if (mc.screen instanceof TitleScreen) {
			OriginBootLog.mark("title-screen-set");
			// The title takes the mouse from here (StartupInputMixin), under the dissolve.
			if (mc.getOverlay() instanceof LoadingOverlay && StartupTransition.startupRevealInProgress()) {
				OriginBootLog.mark("title-input");
			}
			if (mc.getOverlay() == null) {
				OriginBootLog.interactive();
			}
		}
		// -PbootWorld: then a fixed world is opened and timed (OriginBootWorld), and
		// the game closes after that instead of after the title.
		if (OriginBootWorld.ENABLED && OriginBootLog.isInteractive()) {
			OriginBootWorld.tick(mc);
		}
		if (OriginBootWorld.ENABLED ? OriginBootWorld.done() : OriginBootLog.shouldQuit()) {
			mc.stop();
		}
	}
}
