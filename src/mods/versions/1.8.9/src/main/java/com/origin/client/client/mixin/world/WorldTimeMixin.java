package com.origin.client.client.mixin.world;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Time Changer: the CLIENT's perceived time of day, render-side only.
//
// 1.8.9: the sky, sun/moon, star brightness, sky colour and the lightmap's
// daylight all derive from World.getCelestialAngle(partial) (and
// getCelestialAngleRadians / getSunBrightness / getSkyColor on top of it), the
// moon texture from World.getMoonPhase(). Both are answered here from the
// configured time through the world's own provider (so an End/Nether provider
// keeps its fixed sky), for the CLIENT world only. Deliberately NOT
// getWorldTime(): WorldClient.tick writes getWorldTime() + 1 back every tick,
// so overriding the getter would overwrite the real time the server sent; this
// way nothing is stored, the server's clock and mob spawning are untouched, and
// turning the mod off snaps straight back.
@Mixin(World.class)
public abstract class WorldTimeMixin {

	private static boolean originclient$applies(World w) {
		return w.isRemote && Mods.on("timechanger") && w == Minecraft.getMinecraft().theWorld;
	}

	@Inject(method = "getCelestialAngle", at = @At("HEAD"), cancellable = true)
	private void originclient$celestialAngle(float partialTicks, CallbackInfoReturnable<Float> cir) {
		World self = (World) (Object) this;
		try {
			if (originclient$applies(self)) {
				cir.setReturnValue(self.provider.calculateCelestialAngle((long) OriginClientMod.timeOverride, partialTicks));
			}
		} catch (Throwable ignored) {
			// the real sky this frame
		}
	}

	@Inject(method = "getMoonPhase", at = @At("HEAD"), cancellable = true)
	private void originclient$moonPhase(CallbackInfoReturnable<Integer> cir) {
		World self = (World) (Object) this;
		try {
			if (originclient$applies(self)) {
				cir.setReturnValue(self.provider.getMoonPhase((long) OriginClientMod.timeOverride));
			}
		} catch (Throwable ignored) {
			// the real moon this frame
		}
	}
}
