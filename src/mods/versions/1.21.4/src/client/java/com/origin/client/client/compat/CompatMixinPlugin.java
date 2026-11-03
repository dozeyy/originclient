package com.origin.client.client.compat;

import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin plugin of originclient.client.mixins.json: a mixin into another mod (Sodium, Iris, JEI) applies
 * only while CompatGates says its group's mod versions are ones it was proven against. Asked before
 * Mixin looks the target class up, so a skipped mixin logs nothing of its own; CompatGates logs one
 * line per switched-off feature instead. Kept out of the mixin package (a non-mixin class there can't
 * be loaded) and free of game classes (it runs before any of them load).
 *
 * 1.21.4 FORK (overrides.txt): this jar also runs 1.21.2 and 1.21.3 (VERSIONS.md), and a few hooks are
 * written against 1.21.4's item-model shapes (ItemStackRenderState in the target's signature). On 1.21.2/3
 * those fail at APPLY with InvalidInjectionException (defaultRequire 0 does not cover a bad signature), so
 * they are skipped before Mixin ever reads them: the feature stays vanilla there, the rest of Origin works.
 */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
		CompatGates.init();
	}

	/** Hooks whose target signature only exists from 1.21.4 (1.7 Animations' third-person sword turn). */
	private static final Set<String> NEEDS_1_21_4 = Set.of(
			"com.origin.client.client.mixin.OldAnimationsHandLayerMixin");

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (NEEDS_1_21_4.contains(mixinClassName) && !atLeast1214()) return false;
		return CompatGates.mixinAllowed(mixinClassName);
	}

	private static Boolean atLeast1214;

	/** True on 1.21.4 (and anything unparseable, so a surprise never switches a 1.21.4 feature off). */
	private static boolean atLeast1214() {
		if (atLeast1214 == null) {
			boolean ok = true;
			try {
				var mc = FabricLoader.getInstance().getModContainer("minecraft");
				if (mc.isPresent()) ok = VersionPredicate.parse(">=1.21.4-").test(mc.get().getMetadata().getVersion());
			} catch (Exception e) {
				ok = true;
			}
			atLeast1214 = ok;
		}
		return atLeast1214;
	}

	@Override public String getRefMapperConfig() { return null; }
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
