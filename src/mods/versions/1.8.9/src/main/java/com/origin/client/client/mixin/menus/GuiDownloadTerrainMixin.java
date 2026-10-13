package com.origin.client.client.mixin.menus;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.resources.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Downloading terrain" when joining a server or changing dimension (1.16.5's
// ReceivingLevelScreenMixin). HEAD-cancel takeover -> the Origin loading scene.
// 1.8.9: drawScreen only paints (dirt + one centred string + buttons); the
// screen's work -- the keep-alive packet and closing itself -- runs in
// updateScreen, so replacing the draw is safe. The world already exists while
// this shows, which is why it needs its own takeover rather than the
// out-of-world backdrop.
@Mixin(GuiDownloadTerrain.class)
public abstract class GuiDownloadTerrainMixin {

	@Inject(method = "drawScreen", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
		try {
			// Only cancel vanilla when the Origin scene actually drew (fail-soft).
			if (OriginScreenRenderer.renderLoadingScene(new Gfx(), I18n.format("multiplayer.downloadingTerrain"))) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// vanilla's dirt screen
		}
	}
}
