package com.origin.client.client.compat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.Version;

/**
 * Proves the Vulkan-mode gates (2026-11-16) change nothing in OpenGL mode and switch the right things off in
 * Vulkan mode. Run by `gradlew build` (task compatGateTest, wired into check); exits 1 on any failure.
 *
 * <p>OPENGL UNCHANGED. compat-gates.opengl-baseline.txt is this module's gate table exactly as it was before
 * Vulkan mode (origin/main 76dae99). For every mod set below WITHOUT VulkanMod, the live table must make the
 * same decision (on, or the same Mod Menu note) for every group, feature and mixin the baseline names, and
 * every id/mixin the live table adds must be on. The signature check is left out on both sides: it runs only
 * after the version ranges pass, reads the same sig lines from both tables, and the ranges decide identically,
 * so it cannot differ either.
 *
 * <p>VULKAN OFF. With VulkanMod loaded (VulkaIris providing sodium 0.8.12, the bundled Sodium disabled), every
 * Sodium/Iris hook group, the four shader speed-up features, the OpenGL-only hooks and effects are off with the
 * note "Off with Vulkan graphics"; JEI's gate is untouched.
 */
public final class VulkanGateTest {
	private static final List<String> FAILS = new ArrayList<>();

	public static void main(String[] args) throws Exception {
		String baseline = resource("compat-gates.opengl-baseline.txt");
		String live = resource(CompatGates.RESOURCE);

		Map<String, Map<String, String>> openGl = new LinkedHashMap<>();
		openGl.put("bundled Sodium 0.8.13 + Iris 1.8.14-beta.1 + JEI", mods(
				"sodium", "0.8.13+mc1.21.1", "iris", "1.8.14-beta.1+mc1.21.1", "jei", "19.27.0.340"));
		openGl.put("bundled stack + JEI 19.51", mods(
				"sodium", "0.8.13+mc1.21.1", "iris", "1.8.14-beta.1+mc1.21.1", "jei", "19.51.0.418"));
		openGl.put("vetted Sodium 0.6.5 + Iris 1.8.1", mods("sodium", "0.6.5+mc1.21.1", "iris", "1.8.1+mc1.21.1"));
		openGl.put("Sodium 0.8.12 (VulkaIris's provided version) without VulkanMod", mods(
				"sodium", "0.8.12", "iris", "1.8.14-beta.1+mc1.21.1"));
		openGl.put("old Sodium 0.5.11 + Iris 1.7.6 + JEI 20", mods(
				"sodium", "0.5.11+mc1.21", "iris", "1.7.6+mc1.21.1", "jei", "20.0.0.1"));
		openGl.put("Sodium only", mods("sodium", "0.8.13+mc1.21.1"));
		openGl.put("no third-party mods", mods());

		for (Map.Entry<String, Map<String, String>> set : openGl.entrySet()) {
			Map<String, String> before = CompatGates.decide(baseline, lookup(set.getValue()));
			Map<String, String> after = CompatGates.decide(live, lookup(set.getValue()));
			for (Map.Entry<String, String> d : before.entrySet()) {
				String now = after.get(d.getKey());
				check(d.getValue().equals(now), set.getKey() + ": " + d.getKey() + " was '" + d.getValue() + "', now '" + now + "'");
			}
			for (Map.Entry<String, String> d : after.entrySet()) {
				if (!before.containsKey(d.getKey())) {
					check("on".equals(d.getValue()), set.getKey() + ": new " + d.getKey() + " is '" + d.getValue() + "' without VulkanMod");
				}
			}
		}

		// Vulkan mode as the launcher starts it: -Dfabric.debug.disableModIds=sodium,immediatelyfast drops the
		// bundled Sodium; VulkaIris provides sodium 0.8.12; nested Iris 1.8.14-beta.1 and JEI stay.
		Map<String, String> vulkanMods = mods("vulkanmod", "0.6.7+1.21.1", "sodium", "0.8.12",
				"iris", "1.8.14-beta.1+mc1.21.1", "jei", "19.51.0.418");
		Map<String, String> vk = CompatGates.decide(live, lookup(vulkanMods));
		String vkNote = "Off with Vulkan graphics";
		for (String id : new String[] {"iris", "irisHalfRate", "sodium", "sodiumWalk", "smartTerrainPair", "opengl",
				"shaderPerformanceMode", "halfRateShadows", "offMapShadows", "smartTerrainUpdates",
				"colorsaturation", "motionblur"}) {
			check(vkNote.equals(vk.get(id)), "Vulkan: " + id + " is '" + vk.get(id) + "', want '" + vkNote + "'");
		}
		for (String m : new String[] {"IrisShadowDirectivesMixin", "IrisPipelineHalfRateMixin",
				"IrisShadowRendererHalfRateMixin", "SodiumSortedRenderListsAccessor", "SodiumWorldRendererReuseMixin",
				"SodiumRenderSectionManagerAccessor", "SodiumGraphReuseMixin", "SodiumWalkEndMixin",
				"SodiumOcclusionStampMixin", "SodiumViewportShadowMapMixin", "GameRendererPostShadersMixin",
				"LevelRendererCullFrustumMixin"}) {
			check("off".equals(vk.get("mixin " + m)), "Vulkan: mixin " + m + " is '" + vk.get("mixin " + m) + "', want off");
		}
		Map<String, String> noVk = new LinkedHashMap<>(vulkanMods);
		noVk.remove("vulkanmod");
		Map<String, String> jeiBase = CompatGates.decide(live, lookup(noVk));
		check(jeiBase.get("jei").equals(vk.get("jei")) && "on".equals(vk.get("jei")),
				"Vulkan: jei is '" + vk.get("jei") + "', want unchanged 'on'");
		check(jeiBase.get("mixin JeiGuiEventHandlerMixin").equals(vk.get("mixin JeiGuiEventHandlerMixin")),
				"Vulkan: JEI mixins changed");

		// Every Vulkan-off line in the live table is an `absent` need on vulkanmod and nothing else.
		for (String line : live.split("\r?\n")) {
			if (line.trim().startsWith("group") && line.contains(" absent")) {
				check(line.contains("vulkanmod absent") && line.indexOf(" absent") == line.lastIndexOf(" absent"),
						"absent need other than vulkanmod: " + line.trim());
			}
		}

		if (!FAILS.isEmpty()) {
			for (String f : FAILS) {
				System.out.println("FAIL " + f);
			}
			System.out.println("VulkanGateTest: " + FAILS.size() + " failure(s)");
			System.exit(1);
		}
		System.out.println("VulkanGateTest: PASS (" + openGl.size() + " OpenGL mod sets match the pre-Vulkan table; "
				+ "Vulkan mode switches off 12 ids and 12 mixins, JEI untouched)");
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			FAILS.add(what);
		}
	}

	private static Map<String, String> mods(String... idVersion) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i < idVersion.length; i += 2) {
			m.put(idVersion[i], idVersion[i + 1]);
		}
		return m;
	}

	private static CompatGates.ModLookup lookup(Map<String, String> loaded) {
		return mod -> {
			String v = loaded.get(mod);
			if (v == null) {
				return null;
			}
			try {
				return Version.parse(v);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};
	}

	private static String resource(String name) throws Exception {
		try (InputStream in = VulkanGateTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing resource " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
