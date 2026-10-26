package com.origin.client.client;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.extensibility.IRemapper;

/**
 * DEV GAME ONLY (gradlew runClient / CI dev-shots + boot-smoke). Does nothing in a player's game.
 *
 * <p>Why: Iris 1.8.14-beta.1+mc1.21.1 (bundled since the Sodium 0.8.13 move, 2026-10-25) has a hard
 * selector with no descriptor, {@code method = "Lnet/minecraft/class_761;method_22714"} on
 * MixinLevelRenderer.iris$writeRainAndSnowToDepthBuffer. In the Mojang-named dev game Fabric Loader adds
 * MixinIntermediaryDevRemapper to Mixin's remapper chain, and its mapMethodName(owner, name, desc = null)
 * calls unmapDesc(null) when the name is not an intermediary one: NullPointerException, "Mixin apply for
 * mod iris failed mixins.iris.json:MixinLevelRenderer", and the dev game dies on LevelRenderer (CI runs
 * 40278012122, 40278014829, 40278017252, 40278020181). Fabric Loader 0.19.3 and the newest 0.20.1 have
 * the same code. A player's game never adds that remapper (no dev mappings), so it is unaffected.
 *
 * <p>Fix: an empty mixin config (originclient.devremap.mixins.json) whose plugin runs at mixin config
 * selection, before any game class is transformed, and wraps each remapper in the chain so a null
 * descriptor that makes it throw keeps the name as it is. In the dev game Loom already wrote the selector
 * in Mojang names (renderSnowAndRain), so the unchanged name is the right answer. Every other call goes
 * straight through.
 *
 * <p>What breaks if it stops working: only the dev game (runClient, CI dev-shots/boot-smoke) on 1.21.1
 * crashes again with the Iris MixinLevelRenderer failure; players are not affected either way.
 */
public final class DevRemapFix implements IMixinConfigPlugin {

	@Override
	public void onLoad(String mixinPackage) {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}
		try {
			Object chain = MixinEnvironment.getDefaultEnvironment().getRemappers();
			Field f = chain.getClass().getDeclaredField("remappers");
			f.setAccessible(true);
			@SuppressWarnings("unchecked")
			List<IRemapper> list = (List<IRemapper>) f.get(chain);
			for (int i = 0; i < list.size(); i++) {
				IRemapper r = list.get(i);
				if (!(r instanceof NullDescSafe)) {
					list.set(i, new NullDescSafe(r));
				}
			}
			System.out.println("[Origin] dev mixin remapper: null-descriptor guard on " + list.size() + " remapper(s)");
		} catch (Throwable t) {
			System.out.println("[Origin] dev mixin remapper guard not installed: " + t);
		}
	}

	/** Delegates everything; a null descriptor that makes the delegate throw keeps the name unchanged. */
	private record NullDescSafe(IRemapper d) implements IRemapper {
		@Override
		public String mapMethodName(String owner, String name, String desc) {
			if (desc != null) {
				return d.mapMethodName(owner, name, desc);
			}
			try {
				return d.mapMethodName(owner, name, null);
			} catch (NullPointerException e) {
				return name;
			}
		}

		@Override
		public String mapFieldName(String owner, String name, String desc) {
			if (desc != null) {
				return d.mapFieldName(owner, name, desc);
			}
			try {
				return d.mapFieldName(owner, name, null);
			} catch (NullPointerException e) {
				return name;
			}
		}

		@Override
		public String map(String typeName) {
			return d.map(typeName);
		}

		@Override
		public String unmap(String typeName) {
			return d.unmap(typeName);
		}

		@Override
		public String mapDesc(String desc) {
			return d.mapDesc(desc);
		}

		@Override
		public String unmapDesc(String desc) {
			return d.unmapDesc(desc);
		}
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
