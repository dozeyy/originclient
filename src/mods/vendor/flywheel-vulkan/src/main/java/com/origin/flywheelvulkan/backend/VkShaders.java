package com.origin.flywheelvulkan.backend;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import com.origin.flywheelvulkan.FlywheelVulkanClient;
import com.origin.flywheelvulkan.shader.GlslRetarget;
import com.origin.flywheelvulkan.shader.VkLayout;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.LightShader;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.MaterialShaders;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.compile.ContextShader;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.component.InstanceStructComponent;
import dev.engine_room.flywheel.backend.compile.component.SsboInstanceComponent;
import dev.engine_room.flywheel.backend.compile.component.UberShaderComponent;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.backend.glsl.generate.FnSignature;
import dev.engine_room.flywheel.backend.glsl.generate.GlslExpr;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.shader.SPIRVUtils;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Builds Flywheel's own pipeline shaders for Vulkan: the same components in the same order as
 * Flywheel 1.0.6's PipelineCompiler (MIT, Copyright (c) 2021-2024 Jozufozu; see
 * LICENSE_flywheel-vulkan), assembled as ShaderCache.expand does, then {@link GlslRetarget} to
 * GLSL 450 with set/binding/location qualifiers, then VulkanMod's shaderc wrapper (SPIRVUtils).
 *
 * <p>The fog and cutout ubershaders are rebuilt from the same MaterialShaderIndices Flywheel's
 * MaterialEncoder packs into every draw, so the switch indices always agree.
 */
final class VkShaders {
	record ProgramKey(InstanceType<?> type, ContextShader context, LightShader light, MaterialShaders shaders, boolean cutout) {
		static ProgramKey of(InstanceType<?> type, ContextShader context, Material material) {
			return new ProgramKey(type, context, material.light(), material.shaders(), material.cutout() != CutoutShaders.OFF);
		}
	}

	/** vertexSource: the retargeted Flywheel vertex GLSL, kept for the shader-pack splice (PackDraw). */
	record Modules(long vertex, long fragment, String vertexSource) {
	}

	private static final ResourceLocation API_IMPL_VERT = ResourceUtil.rl("internal/api_impl.vert");
	private static final ResourceLocation API_IMPL_FRAG = ResourceUtil.rl("internal/api_impl.frag");
	private static final ResourceLocation COMPONENTS_HEADER_FRAG = ResourceUtil.rl("internal/components_header.frag");
	private static final ResourceLocation MAIN_VERT = ResourceLocation.fromNamespaceAndPath(FlywheelVulkanClient.MOD_ID, "vulkan/main.vert");
	private static final ResourceLocation MAIN_FRAG = ResourceLocation.fromNamespaceAndPath(FlywheelVulkanClient.MOD_ID, "vulkan/main.frag");

	private final Map<ProgramKey, Modules> modules = new HashMap<>();
	private ShaderSources sources;
	private int fogCount = -1;
	private int cutoutCount = -1;
	private UberShaderComponent fog;
	private UberShaderComponent cutout;

	/**
	 * Make sure a material's fog and cutout shaders have ubershader indices, exactly like
	 * PipelineCompiler.get. Returns true when the ubershaders (and so every compiled program)
	 * changed and the caller must rebuild its pipelines.
	 */
	boolean prepare(Material material) {
		MaterialShaderIndices.fogSources().index(material.fog().source());
		MaterialShaderIndices.cutoutSources().index(material.cutout().source());
		MaterialShaderIndices.cutoutSources().index(CutoutShaders.OFF.source());

		ShaderSources current = FlwPrograms.SOURCES;
		int f = MaterialShaderIndices.fogSources().all().size();
		int c = MaterialShaderIndices.cutoutSources().all().size();
		if (current == sources && f == fogCount && c == cutoutCount) {
			return false;
		}
		destroyModules();
		sources = current;
		fogCount = f;
		cutoutCount = c;
		fog = UberShaderComponent.builder(ResourceUtil.rl("fog"))
				.materialSources(MaterialShaderIndices.fogSources().all())
				.adapt(FnSignature.create().returnType("vec4").name("flw_fogFilter").arg("vec4", "color").build(),
						GlslExpr.variable("color"))
				.switchOn(GlslExpr.variable("_flw_uberFogIndex"))
				.build(current);
		cutout = UberShaderComponent.builder(ResourceUtil.rl("cutout"))
				.materialSources(MaterialShaderIndices.cutoutSources().all())
				.adapt(FnSignature.create().returnType("bool").name("flw_discardPredicate").arg("vec4", "color").build(),
						GlslExpr.boolLiteral(false))
				.switchOn(GlslExpr.variable("_flw_uberCutoutIndex"))
				.build(current);
		return true;
	}

	Modules get(ProgramKey key) {
		Modules m = modules.get(key);
		if (m == null) {
			m = compile(key);
			modules.put(key, m);
		}
		return m;
	}

	private Modules compile(ProgramKey key) {
		if (sources == null) {
			throw new IllegalStateException("Flywheel shader sources not loaded");
		}
		List<SourceComponent> vert = List.of(
				sources.get(API_IMPL_VERT),
				new InstanceStructComponent(key.type()),
				sources.get(key.type().vertexShader()),
				sources.get(key.shaders().vertexSource()),
				sources.get(InternalVertex.LAYOUT_SHADER),
				new SsboInstanceComponent(key.type()),
				sources.get(MAIN_VERT));
		List<SourceComponent> frag = new ArrayList<>(List.of(
				sources.get(API_IMPL_FRAG),
				sources.get(key.shaders().fragmentSource()),
				sources.get(COMPONENTS_HEADER_FRAG),
				fog,
				sources.get(key.light().source())));
		frag.add(key.cutout() ? cutout : sources.get(CutoutShaders.OFF.source()));
		frag.add(sources.get(MAIN_FRAG));

		String vs = header("VERTEX_SHADER", key, false) + expand(vert);
		String fs = header("FRAGMENT_SHADER", key, key.cutout()) + expand(frag);
		GlslRetarget.Result r;
		try {
			r = GlslRetarget.retarget(vs, fs, VkLayout.BINDINGS);
		} catch (GlslRetarget.RetargetException e) {
			throw new IllegalStateException("retarget " + name(key) + ": " + e.getMessage(), e);
		}
		long v = module(name(key) + ".vsh", r.vertex(), SPIRVUtils.ShaderKind.VERTEX_SHADER);
		long f;
		try {
			f = module(name(key) + ".fsh", r.fragment(), SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
		} catch (RuntimeException e) {
			vkDestroyShaderModule(Vulkan.getVkDevice(), v, null);
			throw e;
		}
		return new Modules(v, f, r.vertex());
	}

	private static String name(ProgramKey key) {
		return "origin_flw/" + key.type().vertexShader().getPath() + "/" + key.shaders().vertexSource().getPath()
				+ "_" + key.context().nameLowerCase() + (key.cutout() ? "_cutout" : "");
	}

	/** The defines PipelineCompiler's onCompile hooks add, captured instead of written to a GL shader. */
	private static String header(String stage, ProgramKey key, boolean useDiscard) {
		StringBuilder sb = new StringBuilder("#version 450\n#define ").append(stage).append('\n');
		Compilation capture = new Compilation() {
			@Override
			public void define(String k, String v) {
				sb.append("#define ").append(k).append(' ').append(v).append('\n');
			}

			@Override
			public void define(String k) {
				sb.append("#define ").append(k).append('\n');
			}
		};
		key.context().onCompile(capture);
		BackendConfig.INSTANCE.lightSmoothness().onCompile(capture);
		if (useDiscard) {
			capture.define("_FLW_USE_DISCARD");
		}
		return sb.toString();
	}

	/** ShaderCache.expand: each root's includes depth first, once each, then the root. */
	private static String expand(List<SourceComponent> roots) {
		Set<SourceComponent> included = new LinkedHashSet<>();
		for (SourceComponent root : roots) {
			include(included, root);
			included.add(root);
		}
		StringBuilder sb = new StringBuilder();
		for (SourceComponent c : included) {
			sb.append('\n').append(c.source()).append('\n');
		}
		return sb.toString();
	}

	private static void include(Set<SourceComponent> included, SourceComponent component) {
		for (SourceComponent inc : component.included()) {
			include(included, inc);
		}
		included.addAll(component.included());
	}

	static long module(String name, String source, SPIRVUtils.ShaderKind kind) {
		SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(name, source, kind);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer p = stack.mallocLong(1);
			int r = vkCreateShaderModule(Vulkan.getVkDevice(),
					VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv.bytecode()), null, p);
			if (r != VK_SUCCESS) {
				throw new IllegalStateException("vkCreateShaderModule " + name + ": VkResult " + r);
			}
			return p.get(0);
		} finally {
			spirv.free();
		}
	}

	/** Shader modules aren't referenced by built pipelines, so they can go immediately. */
	void destroyModules() {
		for (Modules m : modules.values()) {
			vkDestroyShaderModule(Vulkan.getVkDevice(), m.vertex(), null);
			vkDestroyShaderModule(Vulkan.getVkDevice(), m.fragment(), null);
		}
		modules.clear();
	}
}
