package com.origin.client.client.mixin.menus;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiScreenWorking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// GuiScreenWorking: the IProgressUpdate SCREEN (world conversion, Realms
// downloads, mods reporting progress) -- 1.16.5's ProgressScreenMixin.
// HEAD-cancel takeover -> the Origin loading scene with this screen's own title,
// stage and real percentage (1.8.9's IProgressUpdate reports one).
// 1.8.9: drawScreen also CLOSES the screen once doneWorking is set, so the
// takeover steps aside on that frame and lets vanilla's drawScreen run.
// Shadowed fields are MCP names (two are still unnamed field_ ids in stable_22).
@Mixin(GuiScreenWorking.class)
public abstract class GuiScreenWorkingMixin {
	@Shadow
	private String field_146591_a;   // title (resetProgressAndMessage / displaySavingString)
	@Shadow
	private String field_146589_f;   // stage (displayLoadingString)
	@Shadow
	private int progress;
	@Shadow
	private boolean doneWorking;

	@Inject(method = "drawScreen", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
		try {
			if (doneWorking || OriginStyle.vanillaMenusOn(this)) {
				return;   // vanilla mode: the stock screen
			}
			if (OriginScreenRenderer.renderLoadingScene(new Gfx(), field_146591_a, field_146589_f,
					progress > 0 ? progress : -1)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// vanilla's screen
		}
	}
}
