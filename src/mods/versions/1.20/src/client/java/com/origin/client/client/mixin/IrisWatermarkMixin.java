package com.origin.client.client.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Cross-mod @Pseudo mixin into Iris's ShaderPackScreen to strip the bottom-left
// branding watermark, so Iris's own screen reads as clean as every Origin
// surface (no vanilla/mod overlay text anywhere in the game). The screen draws
// three left-aligned text components at the bottom: irisTextComponent ("Iris
// <version>"), developmentComponent ("Development Environment", dev builds only),
// and updateComponent ("New update available!"). The update line is already gone
// via Iris's own disableUpdateMessage flag (seeded by the launcher / dev config);
// this removes the version + dev labels too. All three are assigned once in the
// constructor, so we blank them at <init> TAIL -- render() then draws empty,
// zero-width components (no NPE, no click target).
//
// <init> is chosen over render() deliberately: it's namespace-invariant, so we
// avoid remapping "render" for a class that isn't Minecraft-mapped. The field
// names are Iris-owned (remap=false); their MutableComponent type is remapped
// with the rest of the mod jar, so the @Shadow descriptors still match Iris's
// intermediary jar at apply. require = 0 + @Pseudo + the config's required=false
// mean this no-ops cleanly if Iris is absent or renames these internals.
@Pseudo
@Mixin(targets = "net.coderbot.iris.gui.screen.ShaderPackScreen", remap = false)
public class IrisWatermarkMixin {

	// irisTextComponent is declared final in Iris; @Mutable strips that at apply
	// so the constructor-TAIL write is legal (a plain @Shadow write to a final
	// field throws IllegalAccessError — it's only allowed from the real <init>).
	@Mutable @Shadow private MutableComponent irisTextComponent;
	@Shadow private MutableComponent developmentComponent;
	@Shadow private MutableComponent updateComponent;

	@Inject(method = "<init>", at = @At("TAIL"), require = 0)
	private void originclient$hideWatermark(CallbackInfo ci) {
		this.irisTextComponent = Component.empty();
		this.developmentComponent = Component.empty();
		this.updateComponent = Component.empty();
	}
}
