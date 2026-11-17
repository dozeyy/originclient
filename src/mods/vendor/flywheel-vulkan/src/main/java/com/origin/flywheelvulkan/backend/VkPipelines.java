package com.origin.flywheelvulkan.backend;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import com.origin.flywheelvulkan.shader.VkLayout;
import com.origin.flywheelvulkan.vk.Retirement;

import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import net.vulkanmod.vulkan.Vulkan;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The shared descriptor set layout + pipeline layout ({@link VkLayout}), and one VkPipeline per
 * (render pass, program, fixed-function state). The fixed-function part mirrors Flywheel's
 * MaterialRenderState.setup (MIT, Jozufozu): cull, depth test, blend, write mask. Polygon offset
 * is the one dynamic piece (VulkanMod's pipelines declare DEPTH_BIAS dynamic too).
 */
final class VkPipelines {
	record StateKey(boolean backfaceCulling, DepthTest depthTest, Transparency transparency, WriteMask writeMask) {
		static StateKey of(Material m) {
			return new StateKey(m.backfaceCulling(), m.depthTest(), m.transparency(), m.writeMask());
		}
	}

	private record PipelineKey(long renderPass, VkShaders.ProgramKey program, StateKey state) {
	}

	final long setLayout;
	final long pipelineLayout;
	private final Map<PipelineKey, Long> pipelines = new HashMap<>();

	VkPipelines() {
		VkDevice dev = Vulkan.getVkDevice();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(VkLayout.BINDING_COUNT, stack);
			int all = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
			for (int i = 0; i < VkLayout.BINDING_COUNT; i++) {
				int type = i < VkLayout.UNIFORM_BLOCKS ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
						: i < VkLayout.DIFFUSE_TEX ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
						: VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
				b.get(i).binding(i).descriptorType(type).descriptorCount(1).stageFlags(all);
			}
			LongBuffer p = stack.mallocLong(1);
			check(vkCreateDescriptorSetLayout(dev, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, p),
					"descriptor set layout");
			setLayout = p.get(0);

			VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
			pcr.get(0).stageFlags(all).offset(0).size(VkLayout.PUSH_SIZE);
			check(vkCreatePipelineLayout(dev, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
					.pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(pcr), null, p), "pipeline layout");
			pipelineLayout = p.get(0);
		}
	}

	long get(long renderPass, VkShaders.ProgramKey program, VkShaders.Modules modules, StateKey state) {
		PipelineKey key = new PipelineKey(renderPass, program, state);
		Long p = pipelines.get(key);
		if (p == null) {
			p = create(renderPass, modules, state);
			pipelines.put(key, p);
		}
		return p;
	}

	/** Pipelines may still be in a frame the GPU hasn't finished, so they go through retirement. */
	void retireAll(Retirement retirement, int slot) {
		for (long p : pipelines.values()) {
			retirement.retire(slot, () -> vkDestroyPipeline(Vulkan.getVkDevice(), p, null));
		}
		pipelines.clear();
	}

	int size() {
		return pipelines.size();
	}

	/** Device idle (or nothing ever submitted). */
	void destroy() {
		VkDevice dev = Vulkan.getVkDevice();
		for (long p : pipelines.values()) {
			vkDestroyPipeline(dev, p, null);
		}
		pipelines.clear();
		vkDestroyPipelineLayout(dev, pipelineLayout, null);
		vkDestroyDescriptorSetLayout(dev, setLayout, null);
	}

	private long create(long renderPass, VkShaders.Modules modules, StateKey state) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(modules.vertex()).pName(stack.UTF8("main"));
			stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(modules.fragment()).pName(stack.UTF8("main"));

			VkVertexInputBindingDescription.Buffer bind = VkVertexInputBindingDescription.calloc(1, stack);
			bind.get(0).binding(0).stride(VkLayout.VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
			VkVertexInputAttributeDescription.Buffer attr = VkVertexInputAttributeDescription.calloc(VkLayout.VERTEX_ATTRIBUTES.length, stack);
			for (int i = 0; i < VkLayout.VERTEX_ATTRIBUTES.length; i++) {
				int[] a = VkLayout.VERTEX_ATTRIBUTES[i];
				attr.get(i).location(a[0]).binding(0).offset(a[1]).format(a[2]);
			}

			VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
			blend(blend.get(0), state.transparency());
			blend.get(0).colorWriteMask(state.writeMask().color() ? 0xF : 0);

			boolean depthTest = state.depthTest() != DepthTest.OFF;

			VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
			ci.get(0).sType$Default()
					.pStages(stages)
					.pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
							.pVertexBindingDescriptions(bind).pVertexAttributeDescriptions(attr))
					.pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
							.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
					.pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
					// Front face CCW + back culling, as VulkanMod's own GraphicsPipeline: same projection,
					// same viewport, so the same winding means "front" for Flywheel and vanilla alike.
					.pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
							.polygonMode(VK_POLYGON_MODE_FILL)
							.cullMode(state.backfaceCulling() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
							.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
							.depthBiasEnable(true)
							.lineWidth(1f))
					.pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
							.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
					.pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
							.depthTestEnable(depthTest)
							.depthWriteEnable(depthTest && state.writeMask().depth())
							.depthCompareOp(compareOp(state.depthTest())))
					.pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
					// Same dynamic set as VulkanMod's GraphicsPipeline (javap 0.6.7): a smaller set would
					// leave VulkanMod's next pipeline reading state this bind left undefined.
					.pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
							.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT,
									VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_LINE_WIDTH)))
					.layout(pipelineLayout)
					.renderPass(renderPass)
					.subpass(0)
					.basePipelineIndex(-1);
			LongBuffer p = stack.mallocLong(1);
			check(vkCreateGraphicsPipelines(Vulkan.getVkDevice(), VK_NULL_HANDLE, ci, null, p), "graphics pipeline");
			return p.get(0);
		}
	}

	/** GL depth funcs from MaterialRenderState.setupDepthTest. */
	static int compareOp(DepthTest t) {
		return switch (t) {
			case OFF, ALWAYS -> VK_COMPARE_OP_ALWAYS;
			case NEVER -> VK_COMPARE_OP_NEVER;
			case LESS -> VK_COMPARE_OP_LESS;
			case EQUAL -> VK_COMPARE_OP_EQUAL;
			case LEQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
			case GREATER -> VK_COMPARE_OP_GREATER;
			case NOTEQUAL -> VK_COMPARE_OP_NOT_EQUAL;
			case GEQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
		};
	}

	/**
	 * GL blend funcs from MaterialRenderState.setupTransparency. ORDER_INDEPENDENT has no Vulkan
	 * OIT pass here (Flywheel's needs its own framebuffers); it draws as plain translucency.
	 */
	static void blend(VkPipelineColorBlendAttachmentState s, Transparency t) {
		switch (t) {
			case OPAQUE -> s.blendEnable(false);
			case ADDITIVE -> on(s, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE);
			case LIGHTNING -> on(s, VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE);
			case GLINT -> on(s, VK_BLEND_FACTOR_SRC_COLOR, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE);
			case CRUMBLING -> on(s, VK_BLEND_FACTOR_DST_COLOR, VK_BLEND_FACTOR_SRC_COLOR, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO);
			case TRANSLUCENT, ORDER_INDEPENDENT -> on(s, VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
					VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
		}
	}

	private static void on(VkPipelineColorBlendAttachmentState s, int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
		s.blendEnable(true)
				.srcColorBlendFactor(srcColor).dstColorBlendFactor(dstColor).colorBlendOp(VK_BLEND_OP_ADD)
				.srcAlphaBlendFactor(srcAlpha).dstAlphaBlendFactor(dstAlpha).alphaBlendOp(VK_BLEND_OP_ADD);
	}

	static void check(int result, String what) {
		if (result != VK_SUCCESS) {
			throw new IllegalStateException(what + " failed: VkResult " + result);
		}
	}
}
