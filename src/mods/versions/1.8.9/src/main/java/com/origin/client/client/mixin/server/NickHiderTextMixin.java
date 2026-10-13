package com.origin.client.client.mixin.server;

import com.origin.client.client.mods.NickHider;
import net.minecraft.client.gui.FontRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Nick Hider: on 1.8.9 every string the game draws or measures -- chat,
// nametags, tab list, scoreboard, titles, already flattened to § Strings --
// reaches FontRenderer through one of these entry points (each takes exactly one
// String): renderString (every draw: drawString / drawStringWithShadow /
// drawSplitString all end there), drawString(String,float,float,int,boolean)
// (the public draw door, in case a FontRenderer build inlines renderString),
// getStringWidth (layout), trimStringToWidth and wrapFormattedStringToWidth
// (cutting and chat wrapping: the replaced string goes in, so the cut indices
// they compute are indices into the replaced string they also return).
// OptiFine REPLACES FontRenderer; HEAD @ModifyVariable on the String argument is
// the injection shape that survives that (no INVOKE / ordinal targets inside its
// bodies). A double pass (drawString -> renderString) is harmless: replace() of
// an already-replaced string finds no names and is a cache hit. NickHider.replace
// returns the same string while the mod is off.
@Mixin(FontRenderer.class)
public class NickHiderTextMixin {

	@ModifyVariable(method = {"renderString", "drawString(Ljava/lang/String;FFIZ)I", "getStringWidth",
			"trimStringToWidth(Ljava/lang/String;IZ)Ljava/lang/String;", "wrapFormattedStringToWidth"},
			at = @At("HEAD"), argsOnly = true)
	private String originclient$hideNames(String text) {
		try {
			return NickHider.replace(text);
		} catch (Throwable t) {
			return text;   // keep the real text
		}
	}
}
