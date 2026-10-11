package com.origin.client.client.mixin;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelTimeAccess;
import org.spongepowered.asm.mixin.Mixin;

// Time Changer's real hook on 1.16.5. The sky/sun/moon path goes through
// LevelTimeAccess: getTimeOfDay(partial) / getMoonPhase() are DEFAULT methods
// there that read dayTime() (intermediary class_5424: method_30274 / _30273
// over method_30271, declared only on that interface this era -- 1.16.2 added
// it). The renderer calls those on the ClientLevel. Level.getDayTime(), which
// LevelTimeMixin hooks, is a different accessor the sky never consults -- that
// is why the old 1.16.5 port notes' "the time-changer works through
// LevelTimeMixin" never held for the sky. Adding a real dayTime() override on
// ClientLevel wins the virtual dispatch for every consumer (sky angle, moon
// phase, star/sky brightness) on the CLIENT only — ServerLevel (the
// integrated server's real clock) is a different class and is never touched,
// and the client's stored LevelData time is left alone (turning the mod off
// snaps straight back to the server's time).
//
// `implements LevelTimeAccess` is load-bearing, not decoration: Loom's
// remapper only renames a mixin method when the MIXIN CLASS's own type
// hierarchy shows what it overrides. Without it, `dayTime` shipped under its
// dev name, never matched the obfuscated interface method in production, and
// the slider did nothing outside runClient. 1.17+ modules implement
// LevelAccessor (which carries the dayTime() default there); on 1.16.5 the
// interface that DECLARES dayTime() is LevelTimeAccess, so it is named
// directly -- the remap can't depend on how LevelAccessor is wired this era.
// ClientLevel already is a LevelTimeAccess (via Level), so the interface adds
// nothing at runtime; the class is abstract so it needn't implement the rest.
// @Override makes CI's compile prove the override exists on 1.16.5.
@Mixin(ClientLevel.class)
public abstract class ClientLevelTimeMixin implements LevelTimeAccess {
	@Override
	public long dayTime() {
		long vanilla = ((Level) (Object) this).getLevelData().getDayTime();
		return Mods.on("timechanger") ? (long) OriginClientMod.timeOverride : vanilla;
	}
}
