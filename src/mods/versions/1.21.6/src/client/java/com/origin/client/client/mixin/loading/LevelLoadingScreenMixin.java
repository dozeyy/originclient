package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Singleplayer "loading terrain" (the chunk-progress map) draws the one Origin loading picture: the
// logo, the bar filled to vanilla's own chunk percentage, vanilla's "Preparing spawn area: N%" line.
//
// PER-VERSION (1.21.6 to 1.21.8, deferred GUI): Screen.renderWithTooltip calls renderBackground
// BEFORE render, each in its own stratum, and LevelLoadingScreen declares no renderBackground
// (javap 1.21.6 / 1.21.8), so the backdrop is cancelled SEPARATELY from the chunk map: this mixin
// ADDS a renderBackground override that draws the picture (its own opaque color.bg) instead of the
// inherited panorama + blur, and render's HEAD cancel drops the chunk map only when that picture drew.
// Render is display-only (the world load runs off the render thread), so cancelling the draw is safe.
// Vanilla mode or a failed picture: super.renderBackground and vanilla's render, the stock screen.
// In the isolated required:false config: if render's target moves, the chunk map shows over the
// picture; nothing crashes.
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin extends Screen {
	// True when this frame's renderBackground drew the Origin picture.
	@Unique
	private boolean originclient$drew;

	private LevelLoadingScreenMixin(Component title) {
		super(title);   // never runs: mixin constructors are discarded
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		originclient$drew = !OriginStyle.vanillaMenusOn(this)
				&& OriginScreenRenderer.renderLoadingScene(guiGraphics, this.getTitle(), originclient$progress());
		if (!originclient$drew) {
			super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
		}
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$dropChunkMap(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (originclient$drew) {
			ci.cancel();
		}
	}

	// Vanilla's own 0-100 count as 0..1, or -1 (sweep) if it can't be read.
	@Unique
	private float originclient$progress() {
		try {
			return Math.max(0, Math.min(100, ((LevelLoadingScreenAccessor) (Object) this).originclient$progressListener().getProgress())) / 100f;
		} catch (Throwable t) {
			return -1f;
		}
	}
}
