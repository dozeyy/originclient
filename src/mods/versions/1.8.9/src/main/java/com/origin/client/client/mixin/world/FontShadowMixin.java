package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.PerfSettings;
import net.minecraft.client.gui.FontRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// SETTINGS > Performance > Disable Text Shadow. FontRenderer.drawString(text, x, y,
// color, dropShadow) is where every shadowed string (drawStringWithShadow too) draws
// its second, offset copy; forcing the flag false at HEAD halves the glyph work for
// chat, HUD, tooltips and menus. Off by default (it changes how text looks). Reads a
// per-frame copy of the setting (PerfSettings), not the config, because this runs
// for every string. If this stops applying, text keeps its shadow.
@Mixin(FontRenderer.class)
public abstract class FontShadowMixin {
	@ModifyVariable(method = "drawString(Ljava/lang/String;FFIZ)I", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private boolean originclient$noShadow(boolean dropShadow) {
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.countFontDrawString();
		}
		return dropShadow && !PerfSettings.noTextShadow;
	}
}
