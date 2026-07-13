package com.origin.client.client.mixin;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

// Time Changer's real hook. The sky/sun/moon path goes through
// LevelTimeAccess.getTimeOfDay -> LevelAccessor.dayTime() — a DEFAULT
// interface method that reads LevelData.getDayTime() directly
// (bytecode-confirmed). Level.getDayTime(), which the old mixin overrode, is
// a different accessor the renderer never consults — that's why the slider
// did nothing. Adding a real dayTime() override on ClientLevel wins the
// virtual dispatch for every consumer (sky angle, moon phase, moon
// brightness) on the client only.
@Mixin(ClientLevel.class)
public abstract class ClientLevelTimeMixin {
	@SuppressWarnings("unused")
	public long dayTime() {
		long vanilla = ((Level) (Object) this).getLevelData().getDayTime();
		return Mods.on("timechanger") ? (long) OriginClientMod.timeOverride : vanilla;
	}
}
