package com.origin.client.client.mixin;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;

// Time Changer's real hook. The sky/sun/moon path goes through
// LevelTimeAccess.getTimeOfDay -> LevelAccessor.dayTime() — a DEFAULT
// interface method that reads LevelData.getDayTime() directly (present on
// 1.17.1, see PORT-NOTES). Level.getDayTime(), which LevelTimeMixin hooks, is
// a different accessor the renderer never consults. Adding a real dayTime()
// override on ClientLevel wins the virtual dispatch for every consumer (sky
// angle, moon phase, moon brightness) on the CLIENT only — ServerLevel (the
// integrated server's real clock) is a different class and is never touched.
//
// `implements LevelAccessor` is load-bearing, not decoration: Loom's remapper
// only renames a mixin method when the MIXIN CLASS's own type hierarchy shows
// what it overrides. Without it, `dayTime` shipped under its dev name, never
// matched the obfuscated interface method in production, and the slider did
// nothing outside runClient. ClientLevel already implements LevelAccessor (via
// Level), so the interface adds nothing at runtime; the class is abstract so
// it needn't implement the rest of LevelAccessor. @Override is safe here (this
// module is not shared-synced) and makes CI's compile prove the override.
@Mixin(ClientLevel.class)
public abstract class ClientLevelTimeMixin implements LevelAccessor {
	@Override
	public long dayTime() {
		long vanilla = this.getLevelData().getDayTime();
		return Mods.on("timechanger") ? (long) OriginClientMod.timeOverride : vanilla;
	}
}
