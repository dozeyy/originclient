package com.origin.client.client.mixin;

import com.origin.client.client.mods.NickHider;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nick Hider: a component lays its text out once and keeps it until the game
// language changes (getVisualOrderText re-does it only while decomposedWith !=
// the current Language; decompiled 1.19.4 / 1.19.1 MutableComponent, same field). Components a server
// sets once and keeps -- tab-list names, for instance -- would otherwise keep
// showing the names from before Nick Hider was switched or its names changed.
// When NickHider's generation moves on, forget the layout so it is redone with
// the current names; between changes this is one int compare.
@Mixin(MutableComponent.class)
public abstract class NickHiderCacheMixin {
	@Shadow
	private Language decomposedWith;

	@Unique
	private int originclient$nickGeneration;

	@Inject(method = "getVisualOrderText", at = @At("HEAD"))
	private void originclient$dropStaleLayout(CallbackInfoReturnable<FormattedCharSequence> cir) {
		int gen = NickHider.generation();
		if (gen != originclient$nickGeneration) {
			originclient$nickGeneration = gen;
			decomposedWith = null;
		}
	}
}
