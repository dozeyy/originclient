package com.origin.flywheelvulkan.backend;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import com.origin.flywheelvulkan.FlywheelVulkanClient;
import com.origin.flywheelvulkan.shader.PackGlsl;
import com.origin.flywheelvulkan.shader.VkLayout;
import com.origin.flywheelvulkan.vk.HostBuffer;
import com.origin.flywheelvulkan.vk.Retirement;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.texture.VulkanImage;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Flywheel instances through the active shader pack's own programs (Vulkairis's
 * {@code ForeignPackPrograms}, reached via {@link PackBridge}): {@link #MAIN} is gbuffers_block
 * (colour and lighting in the main pass), {@link #SHADOW} the pack's shadow program (inside
 * Vulkairis's shadow pass, into the shadow map).
 *
 * <p>A pack pipeline is Flywheel's vertex stage spliced in front of the pack's ({@link PackGlsl}) plus
 * the pack's fragment, over a pipeline layout of set 0 = ours (VkLayout, unchanged) and set 1 = the
 * pack's uniform blocks and samplers; the push range is ours too, so set 0 and the push constants
 * stay valid across a switch between phase-1 and pack pipelines.</p>
 *
 * <p>Fail soft at every step: a role whose fragment won't compile is reported to Vulkairis and stays
 * off for the pack; a (program, state) whose vertex or pipeline won't build is 0 and that draw uses
 * phase 1 (main) or is skipped (shadow). Never flywheel:off, never a throw into the frame.</p>
 */
final class PackDraw {
	static final int SET = 1;
	static final int MAIN = 0;
	static final int SHADOW = 1;
	private static final String[] ROLE_NAMES = { PackBridge.ROLE_BLOCK, PackBridge.ROLE_SHADOW };
	private static final long UBO_ALIGN = 256;
	private static final int SETS_PER_POOL = 64;
	private static final int ALL_STAGES = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
	/**
	 * How many begin() calls a refused role waits before asking Vulkairis again. prepare() returns null
	 * both for a refusal (cached for the pack) and for "programs not registered yet", which does not
	 * move the generation, so a refusal can't simply wait for the generation; this keeps the retry
	 * (a string key and a map lookup in Vulkairis) to once every couple of seconds instead of per frame.
	 */
	static final int RETRY_FRAMES = 120;

	private record PipelineKey(RenderPassFormats pass, VkShaders.ProgramKey program, VkPipelines.StateKey state) {
	}

	/** Per frame slot, per role: the pack's uniform buffer and set-1 descriptors. */
	private static final class SlotRole {
		HostBuffer ubo;
		ByteBuffer[] views;
		final List<Long> pools = new ArrayList<>();
		int poolIndex;
		// Set cache for this frame: diffuse (view, sampler) -> set (almost always just the block atlas).
		// The sampler is part of the key, as in phase 1's SetKey: two materials on one atlas view can
		// differ in blur/mipmap filtering.
		long[] setViews = new long[8];
		long[] setSamplers = new long[8];
		long[] setHandles = new long[8];
		int count;
	}

	private static final class Role {
		final int index;
		final boolean shadow;
		Object program;
		boolean failed;
		/** prepare() returned null at {@link #refusedGeneration}; ask again only every RETRY_FRAMES. */
		boolean refused;
		int refusedGeneration;
		int retryIn;
		long setLayout;
		long layout;
		long fragment;
		int[] uboBindings;
		int[] uboSizes;
		long[] uboOffsets;
		long uboBytes;
		int[] samplerBindings;
		int[] samplerKinds;
		final Map<VkShaders.ProgramKey, Long> vertex = new HashMap<>();
		final Map<PipelineKey, Long> pipelines = new HashMap<>();
		SlotRole[] slots;
		RenderPass lastPass;
		RenderPassFormats formats;
		/** Bumped whenever cached pipelines on VkDraw for this role are stale. */
		int epoch;

		Role(int index) {
			this.index = index;
			this.shadow = index == SHADOW;
		}
	}

	private final VkPipelines pipelines;
	private final Role[] roles = { new Role(MAIN), new Role(SHADOW) };
	private int generation = Integer.MIN_VALUE;
	private boolean active;
	private int frames;
	private final java.util.Set<String> logged = new java.util.HashSet<>();

	PackDraw(VkPipelines pipelines) {
		this.pipelines = pipelines;
	}

	/**
	 * Once per frame per role, before its draws: follows pack changes (retiring everything built for
	 * the old one), builds the role on first use and writes its uniform buffers for this frame slot.
	 *
	 * @return true when this role's pack pipelines may be used this frame
	 */
	boolean begin(int roleIndex, Retirement retirement, int slot, RenderPass renderPass) {
		if (!PackBridge.AVAILABLE) {
			return false;
		}
		try {
			sync(retirement, slot);
			if (!active) {
				return false;
			}
			Role role = roles[roleIndex];
			if (role.failed) {
				return false;
			}
			if (role.program == null) {
				if (role.refused && role.refusedGeneration == generation && --role.retryIn > 0) {
					return false;
				}
				if (!build(role)) {
					return false;
				}
			}
			if (renderPass != role.lastPass) {
				RenderPassFormats f = VkPipelines.formats(renderPass);
				role.lastPass = renderPass;
				if (!f.equals(role.formats)) {
					role.formats = f;
					role.epoch++;
				}
			}
			writeUniforms(role, retirement, slot);
			return true;
		} catch (Throwable t) {
			disableRole(roleIndex, "begin: " + t);
			return false;
		}
	}

	private void sync(Retirement retirement, int slot) {
		int gen = PackBridge.generation();
		boolean nowActive = PackBridge.packActive();
		if (gen != generation || nowActive != active || retirement.frames() != frames) {
			retireAll(retirement, slot);
			generation = gen;
			active = nowActive;
			frames = retirement.frames();
		}
	}

	/** The pack's programs, set layout, pipeline layout and fragment module for a role; false = role off. */
	private boolean build(Role role) {
		String name = ROLE_NAMES[role.index];
		Object program = PackBridge.prepare(name, SET);
		if (program == null) {
			// Not ready yet (pack still registering) or refused (Vulkairis logged why): begin() asks again
			// after RETRY_FRAMES, or at once when the generation moves.
			role.refused = true;
			role.refusedGeneration = generation;
			role.retryIn = RETRY_FRAMES;
			return false;
		}
		role.refused = false;
		VkDevice dev = Vulkan.getVkDevice();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int[] ub = PackBridge.uboBindings(program);
			int[] us = PackBridge.uboSizes(program);
			int[] sb = PackBridge.samplerBindings(program);
			int[] kinds = new int[sb.length];
			for (int i = 0; i < sb.length; i++) {
				kinds[i] = PackBridge.samplerKind(program, i);
			}
			long[] offsets = new long[ub.length];
			long cursor = 0;
			for (int i = 0; i < ub.length; i++) {
				offsets[i] = cursor;
				cursor = InstanceRegions.alignUp(cursor + Math.max(us[i], 16), UBO_ALIGN);
			}

			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(ub.length + sb.length, stack);
			for (int i = 0; i < ub.length; i++) {
				b.get(i).binding(ub[i]).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(ALL_STAGES);
			}
			for (int i = 0; i < sb.length; i++) {
				b.get(ub.length + i).binding(sb[i]).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(ALL_STAGES);
			}
			LongBuffer p = stack.mallocLong(1);
			VkPipelines.check(vkCreateDescriptorSetLayout(dev, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, p),
					"pack descriptor set layout");
			role.setLayout = p.get(0);

			VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
			pcr.get(0).stageFlags(ALL_STAGES).offset(0).size(VkLayout.PUSH_SIZE);
			VkPipelines.check(vkCreatePipelineLayout(dev, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
					.pSetLayouts(stack.longs(pipelines.setLayout, role.setLayout)).pPushConstantRanges(pcr), null, p), "pack pipeline layout");
			role.layout = p.get(0);

			role.fragment = VkShaders.module("pack_" + name + ".fsh", PackBridge.fragmentSource(program), SPIRVUtils.ShaderKind.FRAGMENT_SHADER);

			role.uboBindings = ub;
			role.uboSizes = us;
			role.uboOffsets = offsets;
			role.uboBytes = Math.max(cursor, 16);
			role.samplerBindings = sb;
			role.samplerKinds = kinds;
			role.slots = new SlotRole[frames];
			for (int i = 0; i < frames; i++) {
				role.slots[i] = new SlotRole();
			}
			role.program = program;
			role.epoch++;
			FlywheelVulkanClient.LOGGER.info("[flywheel-vulkan] {} role draws through pack program {} ({} uniform blocks, {} samplers)",
					name, PackBridge.programName(program), ub.length, sb.length);
			return true;
		} catch (Throwable t) {
			destroyRole(role);
			role.failed = true;
			PackBridge.markFailed(name, SET, "flywheel-vulkan could not build it: " + t);
			FlywheelVulkanClient.LOGGER.warn("[flywheel-vulkan] {} role stays unshaded for this pack: {}", name, t.toString());
			return false;
		}
	}

	/**
	 * Switches a role off for the current pack: its draws go back to phase 1 (main) or cast nothing
	 * (shadow). The next pack change or reload ({@link #retireAll}) turns it back on.
	 */
	void disableRole(int roleIndex, String why) {
		Role role = roles[roleIndex];
		if (role.failed) {
			return;
		}
		role.failed = true;
		role.epoch++;
		FlywheelVulkanClient.LOGGER.warn("[flywheel-vulkan] {} role switched off for this pack: {}", ROLE_NAMES[roleIndex], why);
		try {
			PackBridge.markFailed(ROLE_NAMES[roleIndex], SET, why);
		} catch (Throwable t) {
			// Only a courtesy to Vulkairis (so it stops offering the role); the role is already off here.
			FlywheelVulkanClient.LOGGER.debug("[flywheel-vulkan] markFailed not delivered: {}", t.toString());
		}
	}

	/** The role was switched off for this pack (build or draw failure). */
	boolean failed(int roleIndex) {
		return roles[roleIndex].failed;
	}

	/** Vulkairis's pack generation as of the last {@link #begin}. */
	int generation() {
		return generation;
	}

	private void writeUniforms(Role role, Retirement retirement, int slot) {
		SlotRole s = role.slots[slot];
		if (s.ubo == null || s.ubo.size < role.uboBytes) {
			if (s.ubo != null) {
				HostBuffer old = s.ubo;
				retirement.retire(slot, old::destroy);
			}
			s.ubo = HostBuffer.create(role.uboBytes, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
			s.views = new ByteBuffer[role.uboBindings.length];
			for (int i = 0; i < s.views.length; i++) {
				s.views[i] = MemoryUtil.memByteBuffer(s.ubo.ptr() + role.uboOffsets[i], Math.max(role.uboSizes[i], 16));
			}
		}
		for (int i = 0; i < role.uboBindings.length; i++) {
			ByteBuffer v = s.views[i];
			v.clear();
			PackBridge.writeUniforms(role.program, role.uboBindings[i], v, role.uboSizes[i]);
		}
	}

	/** Start of a frame slot (after its fence): the slot's set-1 descriptors go back to the pools. */
	void beginSlot(int slot) {
		VkDevice dev = Vulkan.getVkDevice();
		for (Role role : roles) {
			if (role.slots == null || slot >= role.slots.length) {
				continue;
			}
			SlotRole s = role.slots[slot];
			for (long pool : s.pools) {
				vkResetDescriptorPool(dev, pool, 0);
			}
			s.poolIndex = 0;
			s.count = 0;
		}
	}

	/** A pack was active at the last {@link #begin}. */
	boolean active() {
		return active;
	}

	int epoch(int roleIndex) {
		return roles[roleIndex].epoch;
	}

	long layout(int roleIndex) {
		return roles[roleIndex].layout;
	}

	/** The pack pipeline for a draw, or 0 (that draw keeps phase 1, or casts no shadow). Built once per key. */
	long pipeline(int roleIndex, RenderPass renderPass, VkShaders.ProgramKey program, VkShaders.Modules modules, VkPipelines.StateKey material) {
		Role role = roles[roleIndex];
		VkPipelines.StateKey state = VkPipelines.StateKey.forRole(material, role.shadow);
		PipelineKey key = new PipelineKey(role.formats, program, state);
		Long cached = role.pipelines.get(key);
		if (cached != null) {
			return cached;
		}
		long pipeline = 0;
		try {
			long vertex = vertexModule(role, program, modules);
			if (vertex != 0) {
				pipeline = pipelines.create(renderPass.getId(), vertex, role.fragment, state, role.layout);
			}
		} catch (Throwable t) {
			logOnce(ROLE_NAMES[roleIndex] + " pipeline " + program.type() + "/" + program.context(), t);
		}
		role.pipelines.put(key, pipeline);
		return pipeline;
	}

	private long vertexModule(Role role, VkShaders.ProgramKey program, VkShaders.Modules modules) {
		Long cached = role.vertex.get(program);
		if (cached != null) {
			return cached;
		}
		long module = 0;
		try {
			String source = PackGlsl.splice(modules.vertexSource(), PackBridge.vertexSource(role.program));
			module = VkShaders.module("pack_" + ROLE_NAMES[role.index] + "_" + program.type() + ".vsh", source, SPIRVUtils.ShaderKind.VERTEX_SHADER);
		} catch (Throwable t) {
			logOnce(ROLE_NAMES[role.index] + " vertex " + program.type() + "/" + program.context(), t);
		}
		role.vertex.put(program, module);
		return module;
	}

	private void logOnce(String what, Throwable t) {
		if (logged.add(what)) {
			FlywheelVulkanClient.LOGGER.warn("[flywheel-vulkan] pack {} failed, those draws stay unshaded: {}", what, t.toString());
		}
	}

	/**
	 * The set-1 descriptors for a draw in this slot: the pack's uniform buffers, its images, the
	 * draw's diffuse texture for atlas samplers and the lightmap. 0 when the program has none.
	 *
	 * <p>Never throws: a pack image lookup, a descriptor allocation error or a layout transition that
	 * fails switches the role off for this pack ({@link #disableRole}) and returns 0. The caller checks
	 * {@link #failed} and draws on phase 1 (main) or stops casting (shadow); a throw here would reach
	 * VkEngine's catch, which turns origin:vulkan off for the whole session.</p>
	 */
	long descriptorSet(int roleIndex, int slot, VulkanImage diffuse, VulkanImage lightmap, VulkanImage white) {
		try {
			return descriptorSetOrThrow(roleIndex, slot, diffuse, lightmap, white);
		} catch (Throwable t) {
			disableRole(roleIndex, "descriptor set: " + t);
			return 0;
		}
	}

	private long descriptorSetOrThrow(int roleIndex, int slot, VulkanImage diffuse, VulkanImage lightmap, VulkanImage white) {
		Role role = roles[roleIndex];
		int bindings = role.uboBindings.length + role.samplerBindings.length;
		if (bindings == 0) {
			return 0;
		}
		SlotRole s = role.slots[slot];
		long view = diffuse.getImageView();
		long sampler = diffuse.getSampler();
		for (int i = 0; i < s.count; i++) {
			if (s.setViews[i] == view && s.setSamplers[i] == sampler) {
				return s.setHandles[i];
			}
		}
		long set = allocate(role, s);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bindings, stack);
			for (int i = 0; i < role.uboBindings.length; i++) {
				VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
				info.get(0).buffer(s.ubo.buffer).offset(role.uboOffsets[i]).range(Math.max(role.uboSizes[i], 16));
				writes.get(i).sType$Default().dstSet(set).dstBinding(role.uboBindings[i]).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
						.descriptorCount(1).pBufferInfo(info);
			}
			for (int i = 0; i < role.samplerBindings.length; i++) {
				VulkanImage image = switch (role.samplerKinds[i]) {
					case PackBridge.SAMPLER_ATLAS -> diffuse;
					case PackBridge.SAMPLER_LIGHTMAP -> lightmap;
					default -> PackBridge.image(role.program, i);
				};
				if (image == null) {
					image = white;
				}
				image.readOnlyLayout();
				VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
				info.get(0).imageView(image.getImageView()).sampler(image.getSampler()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
				writes.get(role.uboBindings.length + i).sType$Default().dstSet(set).dstBinding(role.samplerBindings[i])
						.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(info);
			}
			vkUpdateDescriptorSets(Vulkan.getVkDevice(), writes, null);
		}
		if (s.count == s.setViews.length) {
			s.setViews = java.util.Arrays.copyOf(s.setViews, s.count * 2);
			s.setSamplers = java.util.Arrays.copyOf(s.setSamplers, s.count * 2);
			s.setHandles = java.util.Arrays.copyOf(s.setHandles, s.count * 2);
		}
		s.setViews[s.count] = view;
		s.setSamplers[s.count] = sampler;
		s.setHandles[s.count] = set;
		s.count++;
		return set;
	}

	private long allocate(Role role, SlotRole s) {
		VkDevice dev = Vulkan.getVkDevice();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer p = stack.mallocLong(1);
			while (true) {
				if (s.poolIndex == s.pools.size()) {
					s.pools.add(createPool(role));
				}
				int r = vkAllocateDescriptorSets(dev, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
						.descriptorPool(s.pools.get(s.poolIndex)).pSetLayouts(stack.longs(role.setLayout)), p);
				if (r == VK_SUCCESS) {
					return p.get(0);
				}
				if (r != VK11.VK_ERROR_OUT_OF_POOL_MEMORY && r != VK_ERROR_FRAGMENTED_POOL) {
					VkPipelines.check(r, "vkAllocateDescriptorSets (pack)");
				}
				s.poolIndex++;
			}
		}
	}

	private static long createPool(Role role) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int types = (role.uboBindings.length > 0 ? 1 : 0) + (role.samplerBindings.length > 0 ? 1 : 0);
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(types, stack);
			int i = 0;
			if (role.uboBindings.length > 0) {
				sizes.get(i++).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(role.uboBindings.length * SETS_PER_POOL);
			}
			if (role.samplerBindings.length > 0) {
				sizes.get(i).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(role.samplerBindings.length * SETS_PER_POOL);
			}
			LongBuffer p = stack.mallocLong(1);
			VkPipelines.check(vkCreateDescriptorPool(Vulkan.getVkDevice(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
					.maxSets(SETS_PER_POOL).pPoolSizes(sizes), null, p), "vkCreateDescriptorPool (pack)");
			return p.get(0);
		}
	}

	/**
	 * Flywheel's ubershaders changed (VkShaders.prepare returned true): the spliced vertex modules
	 * and the pipelines over them are stale; the roles themselves stay.
	 */
	void invalidatePrograms(Retirement retirement, int slot) {
		VkDevice dev = Vulkan.getVkDevice();
		for (Role role : roles) {
			for (long p : role.pipelines.values()) {
				if (p != 0) {
					retirement.retire(slot, () -> vkDestroyPipeline(dev, p, null));
				}
			}
			role.pipelines.clear();
			for (long m : role.vertex.values()) {
				if (m != 0) {
					vkDestroyShaderModule(dev, m, null);
				}
			}
			role.vertex.clear();
			role.epoch++;
		}
	}

	/** Pack change: everything built for the old pack goes through retirement (a frame may still use it). */
	void retireAll(Retirement retirement, int slot) {
		for (Role role : roles) {
			role.refused = false;
			if (role.program == null && !role.failed && role.layout == 0) {
				role.epoch++;
				continue;
			}
			RoleObjects objects = RoleObjects.take(role);
			retirement.retire(slot, objects::destroy);
			role.program = null;
			role.failed = false;
			role.lastPass = null;
			role.formats = null;
			role.epoch++;
		}
	}

	/** Device idle: destroy everything now. */
	void destroy() {
		for (Role role : roles) {
			RoleObjects.take(role).destroy();
			role.program = null;
		}
	}

	private void destroyRole(Role role) {
		RoleObjects.take(role).destroy();
	}

	/** The Vulkan objects one role owns, detached from it so they can be destroyed later. */
	private record RoleObjects(long setLayout, long layout, long fragment, long[] vertex, long[] pipelines, SlotRole[] slots) {
		static RoleObjects take(PackDraw.Role role) {
			RoleObjects o = new RoleObjects(role.setLayout, role.layout, role.fragment,
					role.vertex.values().stream().mapToLong(Long::longValue).toArray(),
					role.pipelines.values().stream().mapToLong(Long::longValue).toArray(), role.slots);
			role.setLayout = 0;
			role.layout = 0;
			role.fragment = 0;
			role.vertex.clear();
			role.pipelines.clear();
			role.slots = null;
			return o;
		}

		void destroy() {
			VkDevice dev = Vulkan.getVkDevice();
			for (long p : pipelines) {
				if (p != 0) {
					vkDestroyPipeline(dev, p, null);
				}
			}
			for (long m : vertex) {
				if (m != 0) {
					vkDestroyShaderModule(dev, m, null);
				}
			}
			if (fragment != 0) {
				vkDestroyShaderModule(dev, fragment, null);
			}
			if (layout != 0) {
				vkDestroyPipelineLayout(dev, layout, null);
			}
			if (setLayout != 0) {
				vkDestroyDescriptorSetLayout(dev, setLayout, null);
			}
			if (slots != null) {
				for (SlotRole s : slots) {
					if (s.ubo != null) {
						s.ubo.destroy();
					}
					for (long pool : s.pools) {
						vkDestroyDescriptorPool(dev, pool, null);
					}
				}
			}
		}
	}
}
